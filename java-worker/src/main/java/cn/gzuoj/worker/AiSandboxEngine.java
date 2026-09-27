package cn.gzuoj.worker;

import cn.gzuoj.shared.AiCompileTask;
import cn.gzuoj.shared.AiCompileUnit;
import cn.gzuoj.shared.AiGeneratedCaseResult;
import cn.gzuoj.shared.AiSandboxCompletion;
import cn.gzuoj.shared.AiSandboxCompletionStatus;
import cn.gzuoj.shared.AiSandboxFailureStage;
import cn.gzuoj.shared.AiSandboxLease;
import cn.gzuoj.shared.AiSandboxTaskPayload;
import cn.gzuoj.shared.OutputComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 模型生成的源码、输入或答案未通过确定性校验。 */
class AiValidationFailure extends RuntimeException {
    private final AiSandboxFailureStage stage;

    AiValidationFailure(String message, AiSandboxFailureStage stage) {
        super(message);
        this.stage = stage;
    }

    AiSandboxFailureStage getStage() {
        return stage;
    }
}

/** go-judge、网络、租约或返回协议发生基础设施异常。 */
class AiSandboxInfrastructureFailure extends RuntimeException {
    AiSandboxInfrastructureFailure(String message) {
        super(message);
    }
}

/** AI 源码编译后保留在 go-judge 缓存中的程序。 */
record AiCompiledProgram(
    String label,
    String fileName,
    String fileId,
    AiSandboxFailureStage stage
) {}

/** 一次标程或暴力解运行的规范输出与资源证据。 */
record AiProgramOutput(String normalized, String sha256, long timeMs, long memoryKiB) {}

/** 在 go-judge 中执行确定性生成、输入校验和多解差分。 */
@Component
public class AiSandboxEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiSandboxEngine.class);
    private static final String ACCEPTED = "Accepted";
    private static final String STDOUT = "stdout";
    private static final String STDERR = "stderr";
    private static final long MILLISECOND_NS = 1_000_000L;
    private static final long SECOND_NS = 1_000_000_000L;
    private static final long KIB = 1024L;
    private static final long MIB = KIB * KIB;
    private static final long CASE_DATA_LIMIT = 16L * MIB;
    private static final long MAX_TOTAL_DATA_BYTES = 64L * MIB;
    private static final long COMPILE_CPU_SECONDS = 10L;
    private static final long COMPILE_CLOCK_SECONDS = 20L;
    private static final long COMPILE_MEMORY_MIB = 512L;
    private static final long COMPILE_STACK_MIB = 128L;
    private static final int COMPILE_PROCESS_LIMIT = 64;
    private static final long COMPILER_OUTPUT_LIMIT = 64L * KIB;
    private static final long GENERATOR_TIME_LIMIT_MS = 5_000L;
    private static final long GENERATOR_MEMORY_LIMIT_MIB = 256L;
    private static final long VALIDATOR_TIME_LIMIT_MS = 2_000L;
    private static final long VALIDATOR_MEMORY_LIMIT_MIB = 256L;
    private static final long VALIDATOR_OUTPUT_LIMIT = 4L * KIB;
    private static final long MIN_BRUTE_FORCE_TIME_MS = 5_000L;
    private static final long MAX_BRUTE_FORCE_TIME_MS = 30_000L;
    private static final long CLOCK_MULTIPLIER = 3L;
    private static final long CLOCK_GRACE_MS = 1_000L;
    private static final long DEFAULT_STACK_MIB = 64L;
    private static final long RUNTIME_ERROR_LIMIT = 64L * KIB;
    private static final int ERROR_MESSAGE_LIMIT = 2_000;
    private static final List<String> SAFE_ENVIRONMENT = List.of(
        "PATH=/usr/bin:/bin",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "HOME=/tmp"
    );

    private final GoJudgeClient goJudge;

    public AiSandboxEngine(GoJudgeClient goJudge) {
        this.goJudge = goJudge;
    }

    /** 执行一份 AI 沙箱租约，按任务类型分派到全量差分或编译门禁。 */
    public AiSandboxCompletion execute(AiSandboxLease lease, BooleanSupplier leaseStillValid) {
        AiCompileTask compileTask = lease.getCompile();
        if (compileTask != null) {
            return settle(lease, () -> compileOnly(lease, compileTask, leaseStillValid));
        }
        return settle(lease, () -> differential(lease, lease.requireTask(), leaseStillValid));
    }

    private AiSandboxCompletion compileOnly(
        AiSandboxLease lease,
        AiCompileTask task,
        BooleanSupplier leaseStillValid
    ) {
        for (AiCompileUnit unit : task.getUnits()) {
            ensureLease(leaseStillValid);
            compile(unit.getLabel(), unit.getSource(), task.getStage());
        }
        ensureLease(leaseStillValid);
        LOGGER.info(
            "AI 编译门禁通过，jobId={}，阶段={}，产物数={}",
            lease.getJobId(), task.getStage(), task.getUnits().size()
        );
        return new AiSandboxCompletion(
            lease.getAttemptId(),
            lease.getLeaseToken(),
            AiSandboxCompletionStatus.PASSED,
            List.of(),
            false,
            false,
            false,
            0,
            0,
            null,
            null
        );
    }

    /** 执行完整的确定性生成、输入校验和双标程差分。 */
    private AiSandboxCompletion differential(
        AiSandboxLease lease,
        AiSandboxTaskPayload task,
        BooleanSupplier leaseStillValid
    ) {
        ensureLease(leaseStillValid);
        AiCompiledProgram solutionA = compile("标程 A", task.getSolutionASource(), AiSandboxFailureStage.SOLUTIONS);
        ensureLease(leaseStillValid);
        AiCompiledProgram solutionB = compile("标程 B", task.getSolutionBSource(), AiSandboxFailureStage.SOLUTIONS);
        ensureLease(leaseStillValid);
        AiCompiledProgram bruteForce = compile("暴力解", task.getBruteForceSource(), AiSandboxFailureStage.BRUTE_FORCE);
        ensureLease(leaseStillValid);
        AiCompiledProgram generator = compile("测试生成器", task.getGeneratorSource(), AiSandboxFailureStage.TEST_DATA);
        ensureLease(leaseStillValid);
        AiCompiledProgram validator = compile("输入校验器", task.getValidatorSource(), AiSandboxFailureStage.TEST_DATA);

        long totalDataBytes = 0L;
        Set<String> inputHashes = new HashSet<>();
        List<AiGeneratedCaseResult> cases = new ArrayList<>(task.getSeeds().size());
        for (int index = 0; index < task.getSeeds().size(); index++) {
            long seed = task.getSeeds().get(index);
            ensureLease(leaseStillValid);
            String firstInput = generate(generator, seed);
            ensureLease(leaseStillValid);
            String secondInput = generate(generator, seed);
            if (!firstInput.equals(secondInput)) {
                throw new AiValidationFailure(
                    "测试生成器在种子 " + seed + " 下不能复现完全相同的输入",
                    AiSandboxFailureStage.TEST_DATA
                );
            }
            String inputHash = sha256(firstInput);
            if (!inputHashes.add(inputHash)) {
                throw new AiValidationFailure(
                    "不同固定种子生成了重复测试输入，种子：" + seed,
                    AiSandboxFailureStage.TEST_DATA
                );
            }
            validateInput(validator, firstInput, seed);

            ensureLease(leaseStillValid);
            AiProgramOutput outputA = runAnswer(
                solutionA, firstInput, task.getTimeLimitMs(), task.getMemoryLimitMiB()
            );
            ensureLease(leaseStillValid);
            AiProgramOutput outputB = runAnswer(
                solutionB, firstInput, task.getTimeLimitMs(), task.getMemoryLimitMiB()
            );
            if (!outputA.normalized().equals(outputB.normalized())) {
                throw new AiValidationFailure(
                    "两份标程在第 " + (index + 1) + " 个测试点（种子 " + seed + "）输出不一致",
                    AiSandboxFailureStage.SOLUTIONS
                );
            }

            AiProgramOutput bruteOutput = null;
            if (index < task.getBruteForceCaseCount()) {
                ensureLease(leaseStillValid);
                bruteOutput = runAnswer(
                    bruteForce,
                    firstInput,
                    bruteForceTimeLimit(task.getTimeLimitMs()),
                    task.getMemoryLimitMiB()
                );
                if (!bruteOutput.normalized().equals(outputA.normalized())) {
                    throw new AiValidationFailure(
                        "暴力解在第 " + (index + 1) + " 个小数据测试点（种子 " + seed + "）与标程不一致",
                        AiSandboxFailureStage.BRUTE_FORCE
                    );
                }
            }

            totalDataBytes += firstInput.getBytes(StandardCharsets.UTF_8).length;
            totalDataBytes += outputA.normalized().getBytes(StandardCharsets.UTF_8).length;
            if (totalDataBytes > MAX_TOTAL_DATA_BYTES) {
                throw new AiValidationFailure(
                    "生成的测试点输入输出总大小超过 64 MiB",
                    AiSandboxFailureStage.TEST_DATA
                );
            }
            cases.add(new AiGeneratedCaseResult(
                index + 1,
                seed,
                firstInput,
                outputA.normalized(),
                inputHash,
                outputA.sha256(),
                outputA.sha256(),
                outputB.sha256(),
                bruteOutput == null ? null : bruteOutput.sha256(),
                outputA.timeMs(),
                outputA.memoryKiB(),
                outputB.timeMs(),
                outputB.memoryKiB()
            ));
        }

        long maximumTimeMs = cases.stream()
            .mapToLong(value -> Math.max(value.getSolutionATimeMs(), value.getSolutionBTimeMs()))
            .max()
            .orElse(0L);
        long maximumMemoryKiB = cases.stream()
            .mapToLong(value -> Math.max(value.getSolutionAMemoryKiB(), value.getSolutionBMemoryKiB()))
            .max()
            .orElse(0L);
        return new AiSandboxCompletion(
            lease.getAttemptId(),
            lease.getLeaseToken(),
            AiSandboxCompletionStatus.PASSED,
            cases,
            true,
            true,
            true,
            percentage(maximumTimeMs, task.getTimeLimitMs()),
            percentage(maximumMemoryKiB, task.getMemoryLimitMiB() * KIB),
            null,
            null
        );
    }

    /** 把模型问题与基础设施问题分开结算。 */
    private AiSandboxCompletion settle(AiSandboxLease lease, Supplier<AiSandboxCompletion> body) {
        try {
            return body.get();
        } catch (AiValidationFailure failure) {
            LOGGER.info("AI 测试生成未通过，jobId={}，原因={}", lease.getJobId(), failure.getMessage());
            return failed(
                lease,
                AiSandboxCompletionStatus.VALIDATION_FAILED,
                failure.getMessage(),
                failure.getStage()
            );
        } catch (AiSandboxInfrastructureFailure failure) {
            LOGGER.error("AI 沙箱基础设施失败，jobId={}，原因={}", lease.getJobId(), failure.getMessage());
            return failed(lease, AiSandboxCompletionStatus.SYSTEM_ERROR, failure.getMessage(), null);
        } catch (RemoteCallException failure) {
            LOGGER.error("AI 沙箱远程调用失败，jobId={}，原因={}", lease.getJobId(), failure.getMessage());
            return failed(lease, AiSandboxCompletionStatus.SYSTEM_ERROR, failure.getMessage(), null);
        } catch (Exception failure) {
            LOGGER.error("AI 沙箱发生未预期异常，jobId={}", lease.getJobId(), failure);
            String message = failure.getMessage() == null ? "AI 沙箱发生未预期异常" : failure.getMessage();
            return failed(lease, AiSandboxCompletionStatus.SYSTEM_ERROR, message, null);
        }
    }

    /** 编译一份 GNU C++17 源码，并区分模型编译错误和 go-judge 异常。 */
    private AiCompiledProgram compile(String label, String source, AiSandboxFailureStage stage) {
        String sourceName = "main.cpp";
        String artifactName = "program";
        GoJudgeResult result = goJudge.run(new GoJudgeCommand(
            List.of("/usr/bin/g++", sourceName, "-std=gnu++17", "-O2", "-pipe", "-o", artifactName),
            SAFE_ENVIRONMENT,
            outputFiles(COMPILER_OUTPUT_LIMIT),
            COMPILE_CPU_SECONDS * SECOND_NS,
            COMPILE_CLOCK_SECONDS * SECOND_NS,
            COMPILE_MEMORY_MIB * MIB,
            COMPILE_STACK_MIB * MIB,
            COMPILE_PROCESS_LIMIT,
            Map.of(sourceName, new GoJudgeFile(source, null, null, null)),
            List.of(STDOUT, STDERR),
            List.of(artifactName),
            COMPILER_OUTPUT_LIMIT * 2 + MIB,
            false,
            true,
            false
        ));
        if (!result.status().equals(ACCEPTED)) {
            if (isInfrastructureStatus(result.status())) {
                throw new AiSandboxInfrastructureFailure(label + " 编译沙箱异常：" + result.status());
            }
            String message = result.files().getOrDefault(STDERR, "")
                + result.files().getOrDefault(STDOUT, "");
            if (message.isBlank()) {
                message = result.error() == null ? result.status() : result.error();
            }
            throw new AiValidationFailure(label + " 编译失败：" + take(message, ERROR_MESSAGE_LIMIT), stage);
        }
        String fileId = result.fileIds().get(artifactName);
        if (fileId == null) {
            throw new AiSandboxInfrastructureFailure(label + " 编译成功但 go-judge 未返回缓存制品");
        }
        return new AiCompiledProgram(label, artifactName, fileId, stage);
    }

    private String generate(AiCompiledProgram generator, long seed) {
        GoJudgeResult result = runProgram(
            generator,
            "",
            List.of(Long.toString(seed)),
            GENERATOR_TIME_LIMIT_MS,
            GENERATOR_MEMORY_LIMIT_MIB,
            CASE_DATA_LIMIT
        );
        requireAccepted(result, generator, "种子 " + seed);
        return result.files().getOrDefault(STDOUT, "");
    }

    private void validateInput(AiCompiledProgram validator, String input, long seed) {
        GoJudgeResult result = runProgram(
            validator,
            input,
            List.of(),
            VALIDATOR_TIME_LIMIT_MS,
            VALIDATOR_MEMORY_LIMIT_MIB,
            VALIDATOR_OUTPUT_LIMIT
        );
        requireAccepted(result, validator, "种子 " + seed + " 生成的输入");
    }

    private AiProgramOutput runAnswer(
        AiCompiledProgram program,
        String input,
        long timeLimitMs,
        long memoryLimitMiB
    ) {
        GoJudgeResult result = runProgram(
            program, input, List.of(), timeLimitMs, memoryLimitMiB, CASE_DATA_LIMIT
        );
        requireAccepted(result, program, "差分输入");
        String normalized = OutputComparator.INSTANCE.normalize(result.files().getOrDefault(STDOUT, ""));
        return new AiProgramOutput(
            normalized,
            sha256(normalized),
            Math.max(result.time(), 0L) / MILLISECOND_NS,
            Math.max(result.memory(), 0L) / KIB
        );
    }

    /** 在固定网络、文件和进程限制下执行一个已缓存程序。 */
    private GoJudgeResult runProgram(
        AiCompiledProgram program,
        String input,
        List<String> arguments,
        long timeLimitMs,
        long memoryLimitMiB,
        long outputLimit
    ) {
        List<String> args = new ArrayList<>(arguments.size() + 1);
        args.add("./" + program.fileName());
        args.addAll(arguments);
        return goJudge.run(new GoJudgeCommand(
            args,
            SAFE_ENVIRONMENT,
            List.of(
                new GoJudgeFile(input, null, null, null),
                new GoJudgeFile(null, null, STDOUT, outputLimit),
                new GoJudgeFile(null, null, STDERR, RUNTIME_ERROR_LIMIT)
            ),
            timeLimitMs * MILLISECOND_NS,
            (timeLimitMs * CLOCK_MULTIPLIER + CLOCK_GRACE_MS) * MILLISECOND_NS,
            memoryLimitMiB * MIB,
            Math.min(memoryLimitMiB, DEFAULT_STACK_MIB) * MIB,
            1,
            Map.of(program.fileName(), new GoJudgeFile(null, program.fileId(), null, null)),
            List.of(STDOUT, STDERR),
            List.of(),
            outputLimit + RUNTIME_ERROR_LIMIT,
            false,
            true,
            true
        ));
    }

    private void requireAccepted(GoJudgeResult result, AiCompiledProgram program, String context) {
        if (result.status().equals(ACCEPTED)) {
            return;
        }
        if (isInfrastructureStatus(result.status())) {
            throw new AiSandboxInfrastructureFailure(
                program.label() + " 在" + context + "执行时沙箱异常：" + result.status()
            );
        }
        String detail = result.files().getOrDefault(STDERR, "");
        if (detail.isBlank()) {
            detail = result.error() == null ? result.status() : result.error();
        }
        throw new AiValidationFailure(
            program.label() + " 在" + context + "执行失败：" + result.status() + "；"
                + take(detail, ERROR_MESSAGE_LIMIT),
            program.stage()
        );
    }

    private AiSandboxCompletion failed(
        AiSandboxLease lease,
        AiSandboxCompletionStatus status,
        String reason,
        AiSandboxFailureStage stage
    ) {
        return new AiSandboxCompletion(
            lease.getAttemptId(),
            lease.getLeaseToken(),
            status,
            List.of(),
            false,
            false,
            false,
            0,
            0,
            take(reason, ERROR_MESSAGE_LIMIT),
            stage
        );
    }

    private void ensureLease(BooleanSupplier leaseStillValid) {
        if (!leaseStillValid.getAsBoolean()) {
            throw new AiSandboxInfrastructureFailure("AI 任务租约已失效");
        }
    }

    private long bruteForceTimeLimit(long problemTimeLimitMs) {
        return Math.min(Math.max(problemTimeLimitMs * 5L, MIN_BRUTE_FORCE_TIME_MS), MAX_BRUTE_FORCE_TIME_MS);
    }

    private int percentage(long used, long limit) {
        return used <= 0L || limit <= 0L ? 0 : (int) Math.ceil(used * 100.0 / limit);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AiSandboxInfrastructureFailure("SHA-256 算法不可用");
        }
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

    private static String take(String value, int length) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), length));
    }
}
