package ide.program;

import org.junit.jupiter.api.Test;

import java.util.List;

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
