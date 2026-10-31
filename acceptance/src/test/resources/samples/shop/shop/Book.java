package shop;

public class Book implements Item {
    private final String isbn;

    public Book(String isbn) {
        this.isbn = isbn;
    }

    @Override
    public String code() {
        return isbn;
    }
}
