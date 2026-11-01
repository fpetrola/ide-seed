package ide.program;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Identity as a product: addresses are values, ids survive every change of address that a primitive declares. */
class IdentityTest {

    @Test
    void membersKeepTheirIdsWhenTheirTypeOrAParameterTypeIsRenamedOrMoved() {
        var ids = new Identity();
        var money = ids.of(new Address.Type("a.Money"), Kind.CLASS);
        var shop = ids.of(new Address.Type("b.Shop"), Kind.CLASS);
        var pay = ids.of(new Address.Callable(shop, "pay", List.of("int", money.value())), Kind.METHOD);
        var m = ids.of(new Address.Param(pay, "m"), Kind.PARAM);
        var inner = ids.of(new Address.Type("a.Money.Inner"), Kind.CLASS);
        assertThat(ids.render(ids.address(pay))).isEqualTo("b.Shop#pay(int,Money)");

        ids.rebindType("a.Money", "c.Amount");

        assertThat(ids.get(new Address.Type("c.Amount"))).isEqualTo(money);
        assertThat(ids.get(new Address.Type("c.Amount.Inner"))).isEqualTo(inner);
        assertThat(ids.get(new Address.Callable(shop, "pay", List.of("int", money.value())))).isEqualTo(pay);
        assertThat(ids.get(new Address.Param(pay, "m"))).isEqualTo(m);
        assertThat(ids.render(ids.address(pay))).isEqualTo("b.Shop#pay(int,Amount)");
        assertThat(ids.render(ids.address(m))).isEqualTo("b.Shop#pay(int,Amount)/m");
    }

    @Test
    void renameMoveAndResignChangeOneAddressAndKeepTheId() {
        var ids = new Identity();
        var a = ids.of(new Address.Type("a.A"), Kind.CLASS);
        var b = ids.of(new Address.Type("a.B"), Kind.CLASS);
        var x = ids.of(new Address.Field(a, "x"), Kind.FIELD);
        var m = ids.of(new Address.Callable(a, "m", List.of("int")), Kind.METHOD);
        var p = ids.of(new Address.Param(m, "p"), Kind.PARAM);

        ids.rename(x, "y");
        ids.move(m, b);
        ids.resign(m, List.of("int", "long"));

        assertThat(ids.get(new Address.Field(a, "y"))).isEqualTo(x);
        assertThat(ids.has(new Address.Field(a, "x"))).isFalse();
        assertThat(ids.get(new Address.Callable(b, "m", List.of("int", "long")))).isEqualTo(m);
        assertThat(ids.get(new Address.Param(m, "p"))).isEqualTo(p);
        assertThat(ids.render(ids.address(p))).isEqualTo("a.B#m(int,long)/p");
    }

    @Test
    void localsTakeTheirPlacesAmongThoseOfTheirNameAllAtOnce() {
        var ids = new Identity();
        var a = ids.of(new Address.Type("a.A"), Kind.CLASS);
        var m = ids.of(new Address.Callable(a, "m", List.of()), Kind.METHOD);
        var n = ids.of(new Address.Callable(a, "n", List.of()), Kind.METHOD);
        var i = ids.of(new Address.Local(m, "i", 0), Kind.LOCAL);
        var j = ids.of(new Address.Local(m, "j", 0), Kind.LOCAL);
        var k = ids.of(new Address.Local(m, "k", 0), Kind.LOCAL);
        var later = ids.of(new Address.Local(m, "i", 1), Kind.LOCAL);

        ids.placeLocals(n, new LinkedHashMap<>(Map.of(k, "k"))); // k moved into n
        var written = new LinkedHashMap<ElementId, String>();
        written.put(i, "i");
        written.put(j, "i"); // j renamed i, between the two that were
        written.put(later, "i");
        ids.placeLocals(m, written);

        assertThat(ids.get(new Address.Local(n, "k", 0))).isEqualTo(k);
        assertThat(ids.get(new Address.Local(m, "i", 1))).isEqualTo(j);
        assertThat(ids.get(new Address.Local(m, "i", 2))).isEqualTo(later);
        assertThat(ids.has(new Address.Local(m, "j", 0))).isFalse();
        assertThat(ids.render(ids.address(later))).isEqualTo("a.A#m()/i'3");
    }

    @Test
    void anElementReadAnewCanBeTheOneItWas() {
        var ids = new Identity();
        var a = ids.of(new Address.Type("a.A"), Kind.CLASS);
        var x = ids.of(new Address.Field(a, "x"), Kind.FIELD);
        var inner = ids.of(new Address.Type("a.A.Inner"), Kind.CLASS);
        var count = ids.of(new Address.Field(a, "count"), Kind.FIELD); // x, renamed by hand, read anew
        var b = ids.of(new Address.Type("a.B"), Kind.CLASS); // A, renamed by hand, read anew
        ids.of(new Address.Type("a.B.Inner"), Kind.CLASS);
        ids.of(new Address.Field(b, "x"), Kind.FIELD);

        ids.transfer(count, x);
        ids.transfer(b, a);

        assertThat(ids.get(new Address.Field(a, "count"))).isEqualTo(x);
        assertThat(ids.has(new Address.Field(a, "x"))).isFalse();
        assertThat(ids.get(new Address.Type("a.B"))).isEqualTo(a);
        assertThat(ids.get(new Address.Type("a.B.Inner"))).isEqualTo(inner);
        assertThat(ids.has(new Address.Field(b, "x"))).isFalse(); // what was read under the new id is forgotten
        assertThat(ids.address(count)).isNull();
    }

    @Test
    void forgettingATypeForgetsWhatItOwns() {
        var ids = new Identity();
        var a = ids.of(new Address.Type("a.A"), Kind.CLASS);
        var m = ids.of(new Address.Callable(a, "m", List.of()), Kind.METHOD);
        ids.of(new Address.Param(m, "p"), Kind.PARAM);
        ids.of(new Address.Type("a.A.Inner"), Kind.CLASS);
        var other = ids.of(new Address.Type("a.Other"), Kind.CLASS);
        var saved = ids.save();

        ids.forget(a);

        assertThat(ids.addresses()).containsExactly(new Address.Type("a.Other"));
        assertThat(ids.address(m)).isNull();
        ids.restore(saved);
        assertThat(ids.get(new Address.Param(m, "p"))).isNotNull();
        assertThat(ids.get(new Address.Type("a.Other"))).isEqualTo(other);
        assertThat(ids.of(new Address.Type("a.New"), Kind.CLASS).value()).isEqualTo("type:5");
    }
}
