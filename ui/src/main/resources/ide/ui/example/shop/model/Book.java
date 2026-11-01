package shop.model;

/**
 * Try here:
 *  - Alt+Shift+T on "title", then pull-up-unifying: Movie has the same field, and both become Item's.
 *  - Alt+Shift+T on "describe", then pull-up-unifying: the same method, with the same body, in Movie.
 *  - Alt+Shift+T on "pages", then pull-up-field: only Book has it.
 */
public class Book extends Item {
    String title;
    int pages;

    String describe() {
        return "an item of the shop";
    }
}
