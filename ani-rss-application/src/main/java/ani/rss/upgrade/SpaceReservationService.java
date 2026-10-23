package ani.rss.upgrade;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class SpaceReservationService {
    private record Reservation(String volume,long bytes) {}
    private final Map<String,Reservation> reservations=new HashMap<>();
    public synchronized void reserve(String operationId,Path directory,long bytes,long margin) throws IOException {
        if(bytes<0||margin<0) throw new IllegalArgumentException("空间预约参数无效");
        Path parent=directory.toAbsolutePath().normalize(); while(!Files.exists(parent)) parent=parent.getParent();
        FileStore store=Files.getFileStore(parent); String key=(System.getProperty("os.name","").startsWith("Windows")?parent.getRoot()+":":"")+store.name()+":"+store.type();
        long other=reservations.entrySet().stream().filter(e->!e.getKey().equals(operationId)&&e.getValue().volume.equals(key)).mapToLong(e->e.getValue().bytes).sum();
        long usable=store.getUsableSpace();
        if(margin>usable||bytes>usable-margin||other>usable-margin-bytes) throw new IOException("INSUFFICIENT_DISK_SPACE");
        reservations.put(operationId,new Reservation(key,bytes));
    }
    public synchronized void release(String operationId) { reservations.remove(operationId); }
    public synchronized void transfer(String from,String to) {
        Reservation source=reservations.remove(from),existing=reservations.get(to);
        if(source!=null) reservations.put(to,existing==null?source:new Reservation(source.volume,Math.max(source.bytes,existing.bytes)));
    }
}
