package cn.gzuoj.worker;

import cn.gzuoj.shared.JudgeCaseLease;
import cn.gzuoj.shared.JudgeCaseResult;
import cn.gzuoj.shared.JudgeCompletion;
import cn.gzuoj.shared.JudgeExecutionMode;
import cn.gzuoj.shared.JudgeLanguage;
import cn.gzuoj.shared.JudgeLease;
import cn.gzuoj.shared.JudgeStatus;
import cn.gzuoj.shared.OutputComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 编译失败及可向提交者展示的编译器输出。 */
class CompilationFailure extends RuntimeException {
    private final String compilerMessage;

    CompilationFailure(String compilerMessage) {
        super(compilerMessage);
        this.compilerMessage = compilerMessage;
    }

    String getCompilerMessage() {
        return compilerMessage;
    }
}

/** go-judge 或制品服务发生的基础设施异常。 */
class JudgeInfrastructureFailure extends RuntimeException {
    JudgeInfrastructureFailure(String message) {
        super(message);
    }
}

/** 编译后在后续测试点复用的沙箱缓存制品。 */
record CompiledProgram(
    String fileName,
    String fileId,
    List<String> runArgs,
    int processLimit,
    boolean addressSpaceLimit
) {}

/** 四种首版语言的编译一次、逐点运行判题引擎。 */
@Component
public class JudgeEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(JudgeEngine.class);
    private static final String ACCEPTED = "Accepted";
    private static final String STDOUT = "stdout";
    private static final String STDERR = "stderr";
    private static final long MILLISECOND_NS = 1_000_000L;
    private static final long SECOND_NS = 1_000_000_000L;
    private static final long KIB = 1024L;
    private static final long MIB = 1024L * 1024L;
    private static final long COMPILE_CPU_SECONDS = 10L;
    private static final long COMPILE_CLOCK_SECONDS = 20L;
    private static final long COMPILE_MEMORY_MIB = 512L;
    private static final long COMPILE_STACK_MIB = 128L;
    private static final int COMPILE_PROCESS_LIMIT = 64;
    private static final long COMPILER_OUTPUT_LIMIT = 64L * 1024L;
    private static final int COMPILER_MESSAGE_LIMIT = 16_384;
    private static final long USER_OUTPUT_LIMIT = 16L * MIB;
    private static final long RUNTIME_ERROR_LIMIT = 64L * 1024L;
    private static final long CLOCK_MULTIPLIER = 3L;
    private static final long CLOCK_GRACE_MS = 1_000L;
    private static final long DEFAULT_STACK_MIB = 64L;
    private static final int SYSTEM_MESSAGE_LIMIT = 1_000;
    private static final List<String> SAFE_ENVIRONMENT = List.of(
        "PATH=/usr/bin:/bin",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "HOME=/tmp",
        "PYTHONHASHSEED=0"
    );

    private final GoJudgeClient goJudge;
    private final ControlPlaneClient control;

    public JudgeEngine(GoJudgeClient goJudge, ControlPlaneClient control) {
        this.goJudge = goJudge;
        this.control = control;
    }

    /** 执行一份完整提交并形成幂等结算内容。 */
    public JudgeCompletion judge(JudgeLease lease, BooleanSupplier leaseStillValid) {
        try {
            ensureLease(leaseStillValid);
            CompiledProgram program = compile(lease);
            ensureLease(leaseStillValid);
            control.progress(lease, JudgeStatus.JUDGING);
            List<JudgeCaseLease> testCases = new ArrayList<>(lease.getTestCases());
            testCases.sort(Comparator.comparingInt(JudgeCaseLease::getOrdinal));
            List<JudgeCaseResult> caseResults = new ArrayList<>(testCases.size());
            for (JudgeCaseLease testCase : testCases) {
                ensureLease(leaseStillValid);
                caseResults.add(judgeCase(lease, program, testCase));
            }
            return new JudgeCompletion(
                lease.getAttemptId(),
                lease.getLeaseToken(),
                aggregateStatus(lease.getExecutionMode(), caseResults),
                null,
                caseResults,
                null
            );
        } catch (CompilationFailure failure) {
            return new JudgeCompletion(
                lease.getAttemptId(),
                lease.getLeaseToken(),
                JudgeStatus.CE,
                failure.getCompilerMessage(),
                List.of(),
                null
            );
        } catch (JudgeInfrastructureFailure | RemoteCallException failure) {
            return systemFailure(lease, failure.getMessage());
        }
    }

    private JudgeCompletion systemFailure(JudgeLease lease, String message) {
        LOGGER.error("判题基础设施失败，jobId={}，原因={}", lease.getJobId(), message);
        return new JudgeCompletion(
            lease.getAttemptId(),
            lease.getLeaseToken(),
            JudgeStatus.SYSTEM_ERROR,
            null,
            List.of(),
            take(message, SYSTEM_MESSAGE_LIMIT)
        );
    }

    /** 按语言编译源代码并把产物留在 go-judge 缓存。 */
    private CompiledProgram compile(JudgeLease lease) {
        control.progress(lease, JudgeStatus.COMPILING);
        LanguageSpecification specification = languageSpecification(lease.getLanguage());
        GoJudgeResult result = goJudge.run(new GoJudgeCommand(
            specification.compileArgs(),
            SAFE_ENVIRONMENT,
            outputFiles(COMPILER_OUTPUT_LIMIT),
            COMPILE_CPU_SECONDS * SECOND_NS,
            COMPILE_CLOCK_SECONDS * SECOND_NS,
            COMPILE_MEMORY_MIB * MIB,
            COMPILE_STACK_MIB * MIB,
            COMPILE_PROCESS_LIMIT,
            Map.of(specification.sourceName(), new GoJudgeFile(lease.getSourceCode(), null, null, null)),
            List.of(STDOUT, STDERR),
            List.of(specification.artifactName()),
            COMPILER_OUTPUT_LIMIT * 2 + MIB,
            false,
            true,
            false
        ));
        if (!result.status().equals(ACCEPTED)) {
            if (isInfrastructureStatus(result.status())) {
                throw new JudgeInfrastructureFailure("编译沙箱异常：" + result.status());
            }
            String compilerMessage = result.files().getOrDefault(STDERR, "")
                + result.files().getOrDefault(STDOUT, "");
            if (compilerMessage.isBlank()) {
                compilerMessage = result.error() == null ? "编译失败" : result.error();
            }
            throw new CompilationFailure(take(compilerMessage, COMPILER_MESSAGE_LIMIT));
        }
        String fileId = result.fileIds().get(specification.artifactName());
        if (fileId == null) {
            throw new JudgeInfrastructureFailure("编译成功但未返回缓存制品");
        }
        return new CompiledProgram(
            specification.artifactName(),
            fileId,
            specification.runArgs(),
            specification.processLimit(),
            specification.addressSpaceLimit()
        );
    }

    /** 执行单个测试点并只返回脱敏资源信息。 */
    private JudgeCaseResult judgeCase(JudgeLease lease, CompiledProgram program, JudgeCaseLease testCase) {
        byte[] input;
        if (lease.getExecutionMode() == JudgeExecutionMode.RUN) {
            if (testCase.getInlineInput() == null) {
                throw new JudgeInfrastructureFailure("公开运行输入缺失");
            }
            input = testCase.getInlineInput().getBytes(StandardCharsets.UTF_8);
        } else {
            if (testCase.getInputUrl() == null) {
                throw new JudgeInfrastructureFailure("隐藏输入地址缺失");
            }
            if (testCase.getInputSha256() == null) {
                throw new JudgeInfrastructureFailure("隐藏输入哈希缺失");
            }
            input = downloadAndVerify(testCase.getInputUrl(), testCase.getInputSha256());
        }

        byte[] expected;
        if (lease.getExecutionMode() == JudgeExecutionMode.SUBMIT) {
            if (testCase.getExpectedOutputUrl() == null) {
                throw new JudgeInfrastructureFailure("标准输出地址缺失");
            }
            if (testCase.getExpectedOutputSha256() == null) {
                throw new JudgeInfrastructureFailure("标准输出哈希缺失");
            }
            expected = downloadAndVerify(testCase.getExpectedOutputUrl(), testCase.getExpectedOutputSha256());
        } else {
            expected = testCase.getInlineExpectedOutput() == null
                ? null
                : testCase.getInlineExpectedOutput().getBytes(StandardCharsets.UTF_8);
        }

        GoJudgeResult result = goJudge.run(new GoJudgeCommand(
            program.runArgs(),
            SAFE_ENVIRONMENT,
            List.of(
                new GoJudgeFile(new String(input, StandardCharsets.UTF_8), null, null, null),
                new GoJudgeFile(null, null, STDOUT, USER_OUTPUT_LIMIT),
                new GoJudgeFile(null, null, STDERR, RUNTIME_ERROR_LIMIT)
            ),
            lease.getTimeLimitMs() * MILLISECOND_NS,
            (lease.getTimeLimitMs() * CLOCK_MULTIPLIER + CLOCK_GRACE_MS) * MILLISECOND_NS,
            lease.getMemoryLimitMiB() * MIB,
            Math.min(lease.getMemoryLimitMiB(), DEFAULT_STACK_MIB) * MIB,
            program.processLimit(),
            Map.of(program.fileName(), new GoJudgeFile(null, program.fileId(), null, null)),
            List.of(STDOUT, STDERR),
            List.of(),
            USER_OUTPUT_LIMIT + RUNTIME_ERROR_LIMIT,
            false,
            true,
            program.addressSpaceLimit()
        ));
        JudgeStatus status = mapRunStatus(result, expected);
        return new JudgeCaseResult(
            testCase.getCaseId(),
            status,
            Math.max(result.time(), 0L) / MILLISECOND_NS,
            Math.max(result.memory(), 0L) / KIB,
            publicMessage(status),
            lease.getExecutionMode() == JudgeExecutionMode.RUN
                ? result.files().getOrDefault(STDOUT, "")
                : null
        );
    }

    private JudgeStatus mapRunStatus(GoJudgeResult result, byte[] expected) {
        return switch (result.status()) {
            case ACCEPTED -> {
                String actual = result.files().getOrDefault(STDOUT, "");
                if (expected == null || OutputComparator.INSTANCE.matches(
                    new String(expected, StandardCharsets.UTF_8), actual
                )) {
                    yield JudgeStatus.AC;
                }
                yield JudgeStatus.WA;
            }
            case "Time Limit Exceeded" -> JudgeStatus.TLE;
            case "Memory Limit Exceeded" -> JudgeStatus.MLE;
            case "Output Limit Exceeded" -> JudgeStatus.OLE;
            case "Nonzero Exit Status", "Signalled", "Dangerous Syscall" -> JudgeStatus.RE;
            default -> throw new JudgeInfrastructureFailure("运行沙箱异常：" + result.status());
        };
    }

    private JudgeStatus aggregateStatus(JudgeExecutionMode executionMode, List<JudgeCaseResult> results) {
        if (executionMode == JudgeExecutionMode.RUN) {
            return results.stream().allMatch(result -> result.getStatus() == JudgeStatus.AC)
                ? JudgeStatus.AC
                : results.stream()
                    .filter(result -> result.getStatus() != JudgeStatus.AC)
                    .findFirst()
                    .orElseThrow()
                    .getStatus();
        }
        if (!results.isEmpty() && results.stream().allMatch(result -> result.getStatus() == JudgeStatus.AC)) {
            return JudgeStatus.AC;
        }
        if (results.stream().anyMatch(result -> result.getStatus() == JudgeStatus.AC)) {
            return JudgeStatus.PARTIAL;
        }
        return results.stream()
            .filter(result -> result.getStatus() != JudgeStatus.AC)
            .findFirst()
            .map(JudgeCaseResult::getStatus)
            .orElse(JudgeStatus.WA);
    }

    private byte[] downloadAndVerify(String url, String expectedSha256) {
        byte[] bytes = control.download(url);
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new JudgeInfrastructureFailure("SHA-256 算法不可用");
        }
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new JudgeInfrastructureFailure("判题制品哈希校验失败");
        }
        return bytes;
    }

    private void ensureLease(BooleanSupplier leaseStillValid) {
        if (!leaseStillValid.getAsBoolean()) {
            throw new JudgeInfrastructureFailure("任务租约已失效");
        }
    }

    private LanguageSpecification languageSpecification(JudgeLanguage language) {
        return switch (language) {
            case C17 -> new LanguageSpecification(
                "main.c", "main",
                List.of("/usr/bin/gcc", "main.c", "-std=gnu17", "-O2", "-pipe", "-o", "main"),
                List.of("./main"), 1, true
            );
            case CPP17 -> new LanguageSpecification(
                "main.cpp", "main",
                List.of("/usr/bin/g++", "main.cpp", "-std=gnu++17", "-O2", "-pipe", "-o", "main"),
                List.of("./main"), 1, true
            );
            case JAVA21 -> new LanguageSpecification(
                "Main.java", "app.jar",
                List.of(
                    "/bin/sh", "-c",
                    "/usr/bin/javac -encoding UTF-8 Main.java && /usr/bin/jar --create --file app.jar *.class"
                ),
                List.of("/usr/bin/java", "-Dfile.encoding=UTF-8", "-cp", "app.jar", "Main"),
                64, false
            );
            case PYTHON3 -> new LanguageSpecification(
                "main.py", "main.py",
                List.of("/usr/bin/python3", "-m", "py_compile", "main.py"),
                List.of("/usr/bin/python3", "main.py"), 1, true
            );
        };
    }

    private List<GoJudgeFile> outputFiles(long limit) {
        return List.of(
            new GoJudgeFile("", null, null, null),
            new GoJudgeFile(null, null, STDOUT, limit),
            new GoJudgeFile(null, null, STDERR, limit)
        );
    }

    private boolean isInfrastructureStatus(String status) {
        return Set.of("Internal Error", "File Error").contains(status);
    }

    private String publicMessage(JudgeStatus status) {
        return switch (status) {
            case AC -> null;
            case WA -> "答案错误";
            case TLE -> "超过时间限制";
            case MLE -> "超过内存限制";
            case RE -> "运行时错误";
            case OLE -> "超过输出限制";
            default -> null;
        };
    }

    private static String take(String value, int length) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), length));
    }

    private record LanguageSpecification(
        String sourceName,
        String artifactName,
        List<String> compileArgs,
        List<String> runArgs,
        int processLimit,
        boolean addressSpaceLimit
    ) {}
}
