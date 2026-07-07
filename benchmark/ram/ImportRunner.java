import com.powsybl.iidm.network.Network;
import com.powsybl.network.store.client.NetworkStoreService;

import java.nio.file.Path;

/**
 * Imports a network file into a running network-store server through the REST
 * client and reports wall time and client heap. Used together with sample-rss.sh
 * on the server process to profile the write path (see README.md).
 */
public final class ImportRunner {
    public static void main(String[] args) {
        String baseUrl = args[0];
        Path file = Path.of(args[1]);
        long start = System.currentTimeMillis();
        try (NetworkStoreService service = new NetworkStoreService(baseUrl)) {
            Network network = service.importNetwork(file);
            System.out.printf("imported %s in %.1f s%n", network.getId(), (System.currentTimeMillis() - start) / 1000.0);
            System.out.printf("client heap after import: %.0f MB%n",
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0);
        }
    }
}
