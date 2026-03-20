import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class PortfolioHistoryManager {

    public record DailySnapshot(long epochDay, double totalValue) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private static final String DATA_FILE = "portfolio_history.dat";
    private List<DailySnapshot> snapshots = new ArrayList<>();

    public void recordSnapshot(double totalValue) {
        long today = LocalDate.now().toEpochDay();
        snapshots.removeIf(s -> s.epochDay() == today);
        snapshots.add(new DailySnapshot(today, totalValue));
        save();
    }

    public List<DailySnapshot> getSnapshotsForRange(int days) {
        long cutoff = LocalDate.now().toEpochDay() - days;
        return snapshots.stream()
                .filter(s -> s.epochDay() >= cutoff)
                .sorted(Comparator.comparingLong(DailySnapshot::epochDay))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public void load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            snapshots = (List<DailySnapshot>) ois.readObject();
        } catch (Exception ignored) {}
    }

    public void save() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(DATA_FILE))) {
            oos.writeObject(snapshots);
        } catch (Exception ignored) {}
    }
}
