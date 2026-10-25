package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.testclient.central.CentralActions;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.luaj.vm2.LoadState;

/**
 * A deliberately small Lua interpreter for operator-invoked engineering scripts.
 * Only selected public IF-03 commands are exposed. SI-01 has no Lua dependency.
 *
 * Scripts are trusted local engineering inputs, NOT a security sandbox for
 * hostile code. No disk/process/package/Java bridge globals are installed.
 */
final class EngineeringLuaEngine {
    static final int MAX_BATCH = 10_000;
    static final int MAX_SLEEP_MS = 60_000;

    @FunctionalInterface
    interface SimulationSender {
        void send(String registrationId, String profile) throws Exception;
    }

    static final class Stopped extends Error {
        Stopped() { super("Script stopped"); }
    }

    /** Local capability gate: no IF-03 command was sent to the server. */
    static final class CapabilityUnavailable extends Exception {
        CapabilityUnavailable(String message) { super(message); }
    }

    private EngineeringLuaEngine() { }

    static void run(String source, SimulationSender sender,
                    Consumer<String> output, BooleanSupplier valid) {
        run(
                source,
                sender,
                null,
                null,
                output,
                valid);
    }

    static void run(
            String source,
            SimulationSender sender,
            CentralActions centralActions,
            String centralNodeId,
            Consumer<String> output,
            BooleanSupplier valid) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Script is empty");
        }
        if (sender == null || output == null || valid == null) {
            throw new IllegalArgumentException("Missing script dependencies");
        }
        Runnable check = () -> {
            if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) {
                throw new Stopped();
            }
        };

        Globals globals = new Globals();
        globals.load(new BaseLib()); // Classpath-only finder; no filesystem I/O.
        // LuaJ's standard table/string/debug libraries register into
        // package.loaded at initialization. Load the registry internally,
        // then hide its loader entrypoints before evaluating user scripts.
        globals.load(new PackageLib());
        globals.load(new Bit32Lib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        globals.load(new JseMathLib());
        // Hidden instruction hook also stops a CPU-bound Lua while-true loop.
        globals.load(new DebugLib() {
            private int steps;
            @Override public void onInstruction(int pc, Varargs v, int top) {
                if (++steps >= 1024) {
                    steps = 0;
                    check.run();
                }
            }
        });
        globals.set("debug", LuaValue.NIL);
        globals.set("package", LuaValue.NIL);
        globals.set("require", LuaValue.NIL);
        globals.set("module", LuaValue.NIL);
        globals.set("load", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);
        globals.set("collectgarbage", LuaValue.NIL);
        // Do not install os, io, package, luajava, Java reflection or LuaJC.
        LoadState.install(globals);
        LuaC.install(globals);

        LuaTable timing = new LuaTable();
        timing.set("log", new OneArgFunction() {
            @Override public LuaValue call(LuaValue message) {
                check.run();
                output.accept(message.tojstring());
                return LuaValue.NIL;
            }
        });
        timing.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                int ms = arg.checkint();
                if (ms < 0 || ms > MAX_SLEEP_MS) {
                    throw new LuaError("sleep must be 0.." + MAX_SLEEP_MS + " ms");
                }
                sleep(ms, check);
                return LuaValue.NIL;
            }
        });
        timing.set("simulate", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue regId, LuaValue profile) {
                send(regId.checkjstring(), profile.optjstring("normal"),
                        sender, output, check);
                return LuaValue.TRUE;
            }
        });
        timing.set("batch", new OneArgFunction() {
            @Override public LuaValue call(LuaValue options) {
                LuaTable opts = options.checktable();
                int count = opts.get("count").checkint();
                if (count < 1 || count > MAX_BATCH) {
                    throw new LuaError("count must be 1.." + MAX_BATCH);
                }
                int from = opts.get("from").optint(1);
                int to = opts.get("to").optint(2000);
                int interval = opts.get("intervalMs").optint(500);
                if (interval < 0 || interval > MAX_SLEEP_MS) {
                    throw new LuaError("intervalMs must be 0.." + MAX_SLEEP_MS);
                }
                String orderValue = opts.get("order").optjstring("linear")
                        .toLowerCase(Locale.ROOT);
                SimulationBatchPlan.Order order;
                if ("linear".equals(orderValue)) order = SimulationBatchPlan.Order.ASCENDING;
                else if ("random".equals(orderValue)) order = SimulationBatchPlan.Order.RANDOM;
                else throw new LuaError("order must be linear or random");
                long seed = opts.get("seed").optlong(1L);
                String profile = opts.get("profile").optjstring("normal");
                if (!List.of("simple", "normal", "edge").contains(profile)) {
                    throw new LuaError("profile must be simple, normal or edge");
                }
                List<String> ids = SimulationBatchPlan.registrationIds(
                        count, from, to, order, seed);
                output.accept("Batch started: " + count + " tags, " + orderValue
                        + ", interval " + interval + " ms");
                for (int i = 0; i < ids.size(); i++) {
                    check.run();
                    send(ids.get(i), profile, sender, output, check);
                    output.accept((i + 1) + "/" + ids.size() + " " + ids.get(i));
                    if (i + 1 < ids.size()) sleep(interval, check);
                }
                output.accept("Batch complete: " + ids.size() + " registrations");
                return LuaValue.valueOf(ids.size());
            }
        });
        globals.set("timing", timing);

        LuaTable central = new LuaTable();
        central.set("team_set", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue regId, LuaValue teamId) {
                return centralAction(
                        centralActions,
                        centralNodeId,
                        output,
                        check,
                        () -> centralActions.setTeamAssignment(
                                centralNodeId,
                                regId.checkjstring(),
                                teamId.checkjstring()));
            }
        });
        central.set("team_del", new OneArgFunction() {
            @Override public LuaValue call(LuaValue regId) {
                return centralAction(
                        centralActions,
                        centralNodeId,
                        output,
                        check,
                        () -> centralActions.deleteTeamAssignment(
                                centralNodeId,
                                regId.checkjstring()));
            }
        });
        central.set("stage_start_set", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue regId, LuaValue start) {
                return centralAction(
                        centralActions,
                        centralNodeId,
                        output,
                        check,
                        () -> centralActions.setStageStart(
                                centralNodeId,
                                regId.checkjstring(),
                                start.checkjstring()));
            }
        });
        central.set("stage_start_del", new OneArgFunction() {
            @Override public LuaValue call(LuaValue regId) {
                return centralAction(
                        centralActions,
                        centralNodeId,
                        output,
                        check,
                        () -> centralActions.deleteStageStart(
                                centralNodeId,
                                regId.checkjstring()));
            }
        });
        central.set("timing_data_req", new OneArgFunction() {
            @Override public LuaValue call(LuaValue fromSequence) {
                long from = fromSequence.checklong();
                return centralAction(
                        centralActions,
                        centralNodeId,
                        output,
                        check,
                        () -> centralActions.requestTimingData(
                                centralNodeId,
                                from));
            }
        });
        globals.set("central", central);

        // Optional convenience: Lua print() is routed into Script Output.
        globals.set("print", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                check.run();
                StringBuilder text = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1) text.append("\t");
                    text.append(args.arg(i).tojstring());
                }
                output.accept(text.toString());
                return LuaValue.NONE;
            }
        });
        check.run();
        globals.load(source, "engineering-script").call();
        check.run();
    }

    private static LuaValue centralAction(
            CentralActions centralActions,
            String nodeId,
            Consumer<String> output,
            Runnable check,
            LongSupplier action) {
        check.run();
        if (centralActions == null
                || nodeId == null
                || nodeId.isBlank()) {
            throw new LuaError("Central action unavailable: no selected TimingNode");
        }

        final long packetNr;
        try {
            packetNr = action.getAsLong();
        } catch (RuntimeException ex) {
            check.run();
            String message =
                    ex.getMessage() == null
                            ? ex.getClass().getSimpleName()
                            : ex.getMessage();
            throw new LuaError("Central action rejected: " + message);
        }
        check.run();
        output.accept(
                packetNr == 0L
                        ? "Central action queued behind pending DN_PACKET"
                        : "Central DN_PACKET #" + packetNr + " queued");
        return LuaValue.valueOf(packetNr);
    }

    private static void send(String registrationId, String profile,
                             SimulationSender sender, Consumer<String> output,
                             Runnable check) {
        check.run();
        if (registrationId.isBlank()) throw new LuaError("regId is empty");
        if (!List.of("simple", "normal", "edge").contains(profile)) {
            throw new LuaError("unknown profile: " + profile);
        }
        try {
            sender.send(registrationId, profile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Stopped();
        } catch (CapabilityUnavailable ex) {
            check.run();
            throw new LuaError("Simulation not submitted: " + ex.getMessage());
        } catch (Exception ex) {
            check.run();
            throw new LuaError("IF-03 simulation rejected: " + ex.getMessage());
        }
        check.run();
    }

    private static void sleep(int millis, Runnable check) {
        check.run();
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Stopped();
        }
        check.run();
    }
}
