package shop;

class Shop {
    Catalog<Book> books = new Catalog<>();
    Catalog.Entry entry = new Catalog.Entry("k");

    String first() {
        return books.find("x").map(Book::code).orElse("");
    }

    int count() {
        return Catalog.created;
    }

    java.util.List<String> labels() {
        return books.map(b -> b.label());
    }
}
