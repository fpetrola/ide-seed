package orders.model;

/** Anything with an identity. */
public abstract class Entity {
    protected long id;

    public long getId() {
        return id;
    }
}
