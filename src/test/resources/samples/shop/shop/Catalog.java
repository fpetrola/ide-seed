package shop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class Catalog<T extends Item> {
    public static int created;

    private final Map<String, T> items = new HashMap<>();

    public static class Entry {
        public final String key;

        public Entry(String key) {
            this.key = key;
        }
    }

    public class Cursor {
        int index;

        T current() {
            return list().get(index);
        }
    }

    public void add(T item) {
        items.put(item.code(), item);
        created++;
    }

    @SafeVarargs
    public final void addAll(T... more) {
        for (T t : more) {
            add(t);
        }
    }

    public List<T> list() {
        return new ArrayList<>(items.values());
    }

    public <R> List<R> map(Function<? super T, R> f) {
        return list().stream().map(f).toList();
    }

    public Optional<T> find(String code) {
        return Optional.ofNullable(items.get(code));
    }
}
