package org.corfudb.infrastructure;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utils class for the infrastructure module.
 * <p>
 * Created by maithem on 4/4/18.
 */

public class Utils {

    /**
     * This method fsyncs a directory.
     *
     * @param dir the directory to be synced
     * @throws IOException
     */
    public static void syncDirectory(String dir) throws IOException {
        Path dirPath = Paths.get(dir);
        try (FileChannel channel = FileChannel.open(dirPath)) {
            channel.force(true);
        }
    }
}
