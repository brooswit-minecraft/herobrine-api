package dev.herobrine.api.core;

import java.util.List;

/** Paginated list (spec 13.3). Reads {@code offset} and {@code limit} from request params. */
public record Page<T>(List<T> items, int total, int offset, int limit, Integer nextOffset) {
    public static <T> Page<T> of(List<T> all, Params p, int defaultLimit, int maxLimit) {
        int limit = Math.max(1, Math.min(p.integer("limit", defaultLimit), maxLimit));
        int offset = Math.max(0, p.integer("offset", 0));
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        return new Page<>(List.copyOf(all.subList(from, to)), all.size(), offset, limit, to < all.size() ? to : null);
    }
}
