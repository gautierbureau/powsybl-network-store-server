import com.powsybl.network.store.client.NetworkStoreService;
import com.powsybl.network.store.client.PreloadingStrategy;
import com.powsybl.iidm.network.Network;

import java.util.UUID;

/**
 * Reads a network back from the network-store with a given preloading strategy
 * and walks it (bus view + limits) to force full materialization, printing
 * timing and heap usage at each step.
 */
public final class ReadRunner {
    public static void main(String[] args) {
        String baseUrl = args[0];
        UUID networkUuid = UUID.fromString(args[1]);
        PreloadingStrategy strategy = PreloadingStrategy.valueOf(args.length > 2 ? args[2] : "COLLECTION");

        long t0 = System.currentTimeMillis();
        try (NetworkStoreService service = new NetworkStoreService(baseUrl, strategy)) {
            Network network = service.getNetwork(networkUuid);
            step(t0, "getNetwork " + network.getId() + " [" + strategy + "]");

            long lines = network.getLineCount();
            long twt = network.getTwoWindingsTransformerCount();
            long gens = network.getGeneratorStream().count();
            long loads = network.getLoadStream().count();
            long switches = network.getSwitchCount();
            step(t0, "counts: lines=" + lines + " twt=" + twt + " gens=" + gens
                    + " loads=" + loads + " switches=" + switches);

            // force terminal/bus-view computation over the whole network
            long connectedBuses = network.getBusView().getBusStream().count();
            step(t0, "busView buses=" + connectedBuses);

            // touch operational limits on every line side
            final double[] acc = {0};
            network.getLines().forEach(l -> {
                l.getCurrentLimits1().ifPresent(cl -> acc[0] += cl.getPermanentLimit());
                l.getCurrentLimits2().ifPresent(cl -> acc[0] += cl.getPermanentLimit());
            });
            step(t0, "limits walked, checksum=" + String.format("%.1f", acc[0]));

            // touch extensions
            long apc = network.getGeneratorStream()
                    .filter(g -> g.getExtensionByName("activePowerControl") != null)
                    .count();
            step(t0, "generators with activePowerControl=" + apc);
        }
        step(t0, "done");
    }

    private static void step(long t0, String label) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        System.out.printf("[%7.1fs] heapUsed=%dMB %s%n", (System.currentTimeMillis() - t0) / 1000.0, usedMb, label);
        System.out.flush();
    }
}
