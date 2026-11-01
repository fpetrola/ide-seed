package shop.model;

/** Its title and describe() are Book's twins: unify them from Book (pull-up-unifying). */
public class Movie extends Item {
    String title;
    int minutes;

    String describe() {
        return "an item of the shop";
    }
}
