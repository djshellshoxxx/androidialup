package io.circuitdrift.androidialup.modem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes parsed command lines against the active {@link ModemProfile} and produces result
 * codes (S1 AT/DTE sections 3 and 5).
 *
 * <p>Commands execute left-to-right. A runtime failure (unknown S-register, out-of-range value,
 * rejected mode change, {@code ATO} without a call) stops execution and the line ends with a
 * single {@code ERROR}. Dial and answer defer their result to the state owner, so the line has
 * no terminal result. Quiet mode suppresses all output lines but not effects.
 */
public final class AtEngine {

    private static final String IDENTITY_PAGE_4 =
            "modes=AUTO,BYTE_RELAY,PCM_VBD_EXPERIMENTAL;"
                    + "net=AUTO,WIFI_ONLY,CELLULAR_ONLY,PREFER_WIFI,PREFER_CELLULAR";

    private final ModemProfile profile = ModemProfile.factory();
    private final String buildId;

    /** Creates an engine with factory settings; {@code buildId} is reported by {@code ATI3}. */
    public AtEngine(String buildId) {
        this.buildId = Objects.requireNonNull(buildId, "buildId");
    }

    /** The active profile mutated by E/Q/V/S/+MODE/+NET/+DIAG/Z/&F. */
    public ModemProfile profile() {
        return profile;
    }

    public String buildId() {
        return buildId;
    }

    /** Formats a result code as text or numeric according to the current V setting. */
    public String formatResult(ResultCode result) {
        return profile.verbose() ? result.text() : Integer.toString(result.number());
    }

    /** Executes a parsed command line with the default idle context. */
    public AtExecution execute(List<AtCommand> commands) {
        return execute(commands, AtContext.DEFAULT);
    }

    /** Executes a parsed command line in the given context. */
    public AtExecution execute(List<AtCommand> commands, AtContext context) {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(context, "context");
        Run run = new Run(context);
        for (AtCommand command : commands) {
            if (!run.apply(command)) {
                break;
            }
        }
        return run.finish();
    }

    /** Mutable per-line execution state. */
    private final class Run {
        private final AtContext context;
        private final List<String> info = new ArrayList<>();
        private final List<AtEffect> effects = new ArrayList<>();
        private ResultCode terminal = ResultCode.OK;

        Run(AtContext context) {
            this.context = context;
        }

        /** Applies one command; returns false when execution must stop. */
        boolean apply(AtCommand command) {
            if (command instanceof AtCommand.Attention) {
                return true;
            }
            if (command instanceof AtCommand.Echo echo) {
                profile.setEcho(echo.enabled());
                return true;
            }
            if (command instanceof AtCommand.Quiet quiet) {
                profile.setQuiet(quiet.enabled());
                return true;
            }
            if (command instanceof AtCommand.Verbose verbose) {
                profile.setVerbose(verbose.enabled());
                return true;
            }
            if (command instanceof AtCommand.Factory) {
                profile.restoreFactory();
                return true;
            }
            if (command instanceof AtCommand.Reset) {
                profile.restoreFactory();
                if (context.activeCall()) {
                    effects.add(AtEffect.of(AtEffectType.HANGUP));
                }
                return true;
            }
            if (command instanceof AtCommand.Hangup) {
                effects.add(AtEffect.of(AtEffectType.HANGUP));
                return true;
            }
            if (command instanceof AtCommand.Answer) {
                effects.add(AtEffect.of(AtEffectType.ANSWER));
                terminal = null;
                return false;
            }
            if (command instanceof AtCommand.Dial dial) {
                effects.add(new AtEffect(AtEffectType.DIAL, dial.target()));
                terminal = null;
                return false;
            }
            if (command instanceof AtCommand.Online) {
                if (!context.activeCall() || !context.onlineCommand()) {
                    return fail();
                }
                effects.add(AtEffect.of(AtEffectType.RESUME_ONLINE));
                terminal = ResultCode.CONNECT;
                return false;
            }
            if (command instanceof AtCommand.Identify identify) {
                info.add(identityPage(identify.page()));
                return true;
            }
            if (command instanceof AtCommand.SQuery query) {
                if (!profile.hasSRegister(query.register())) {
                    return fail();
                }
                info.add(Integer.toString(profile.sRegister(query.register())));
                return true;
            }
            if (command instanceof AtCommand.SSet set) {
                if (!profile.hasSRegister(set.register()) || set.value() < 0 || set.value() > 255) {
                    return fail();
                }
                profile.setSRegister(set.register(), set.value());
                return true;
            }
            if (command instanceof AtCommand.Plus plus) {
                return applyPlus(plus);
            }
            return fail();
        }

        private boolean applyPlus(AtCommand.Plus plus) {
            boolean query = plus.operation() == AtCommand.Plus.Operation.QUERY;
            String value = plus.value() == null ? "" : plus.value();
            switch (plus.name()) {
                case "MODE" -> {
                    if (query) {
                        info.add("+MODE: " + profile.mode());
                        return true;
                    }
                    if (context.activeCall()) {
                        return fail();
                    }
                    try {
                        profile.setMode(ModemMode.valueOf(value.toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        return fail();
                    }
                    return true;
                }
                case "NET" -> {
                    if (query) {
                        info.add(
                                "+NET: policy=" + profile.networkPolicy()
                                        + ",selected=" + context.selectedNetwork()
                                        + ",validated=" + (context.validated() ? 1 : 0)
                                        + ",metered=" + (context.metered() ? 1 : 0));
                        return true;
                    }
                    try {
                        profile.setNetworkPolicy(NetworkPolicy.valueOf(value.toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        return fail();
                    }
                    return true;
                }
                case "DIAG" -> {
                    if (query) {
                        info.add(
                                "+DIAG: modem=" + context.modemState()
                                        + ",session=" + context.sessionState()
                                        + ",extended=" + (profile.extendedDiagnostics() ? 1 : 0));
                        return true;
                    }
                    if (!value.equals("0") && !value.equals("1")) {
                        return fail();
                    }
                    profile.setExtendedDiagnostics(value.equals("1"));
                    return true;
                }
                default -> {
                    return fail();
                }
            }
        }

        private boolean fail() {
            terminal = ResultCode.ERROR;
            return false;
        }

        private String identityPage(int page) {
            return switch (page) {
                case 0 -> "AndroidDialup";
                case 1 -> "Beta 0.1";
                case 2 -> "protocol=1";
                case 3 -> buildId;
                case 4 -> IDENTITY_PAGE_4;
                default -> throw new IllegalArgumentException("ATI page " + page);
            };
        }

        AtExecution finish() {
            Optional<ResultCode> result = Optional.ofNullable(terminal);
            if (profile.quiet()) {
                return new AtExecution(List.of(), effects, result);
            }
            List<String> lines = new ArrayList<>(info);
            result.ifPresent(code -> lines.add(formatResult(code)));
            return new AtExecution(lines, effects, result);
        }
    }
}
