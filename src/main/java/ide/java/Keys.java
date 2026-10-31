package ide.java;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import ide.program.Address;
import ide.program.Identity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parameter type keys for callable addresses: the resolved type as described, with every workspace type replaced by
 * its id (so renaming or moving a type changes no callable's address) and every other type written by its simple
 * name. Declarations and uses are keyed the same way, so they agree.
 */
final class Keys {

    private static final Pattern TOKEN = Pattern.compile("[\\w$.]+");

    private final Identity ids;

    Keys(Identity ids) {
        this.ids = ids;
    }

    List<String> of(CallableDeclaration<?> callable) {
        return callable.getParameters().stream().map(p -> of(p.getType())).toList();
    }

    List<String> of(ResolvedMethodLikeDeclaration callable) {
        var keys = new ArrayList<String>();
        for (var i = 0; i < callable.getNumberOfParams(); i++) keys.add(of(callable.getParam(i).getType()));
        return keys;
    }

    String of(Type type) {
        return of(Resolution.describe(type));
    }

    String of(ResolvedType type) {
        return of(type.describe());
    }

    private String of(String described) {
        return TOKEN.matcher(described).replaceAll(m -> {
            var id = ids.types().get(m.group());
            return Matcher.quoteReplacement(id != null ? id.value() : Address.simpleName(m.group()));
        }).replace(" ", "");
    }
}
