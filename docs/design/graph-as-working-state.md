# El grafo como estado de trabajo

Documento de diseño del 2026-11-02. Explica la propuesta de la etapa 4 de [ARCHITECTURE.md](../../ARCHITECTURE.md)
(decisión 37, propuesta): por qué hace falta, cómo funcionaría y qué queda propio de cada lenguaje. Incluye un
ejemplo completo, extract superclass, pieza por pieza.

## 1. El problema

IdeSeed dice que el programa es un grafo y el texto una proyección. Medido sobre proyectos reales, el IDE es lento y
no entra en memoria donde IntelliJ sí. Las mediciones son con heap de 2 GB, lo que la JVM da por defecto en una
máquina de 7 GB:

| Proyecto | Fuentes | Leer y resolver | Compilar (ECJ) | Heap vivo |
|---|---|---|---|---|
| IdeSeed | 301 archivos, 2,3 MB | 43 s | 4,4 s | ~430 MB |
| oozx | 395 archivos, 4,1 MB | 38 s | 4,7 s, y se cae | ~880 MB |
| zx-lift/zx-game-run | 38 archivos, 8,6 MB | 13 s en total | | 1,4 GB |
| zx-lift completo | 5757 archivos, 53 MB | no entra en 2 GB | | |

| Operación, con IdeSeed ya leído | Tiempo |
|---|---|
| Rename de un método poco usado | 1,3 s |
| Rename de una clase poco usada | 35 s |
| Rename de `Program#element(ElementId)`, ~300 usos, sin choque de nombres | no terminó en 15 minutos |

La causa no es el grafo: es que **el driver de Java trabaja al revés del diseño**.

- El grafo se arma desde el texto en cada apertura, resolviendo cada uso con el symbol solver de JavaParser.
- Cada primitivo se escribe en el texto en el momento, y para saber qué pasó se vuelve a leer y a resolver. Si
  cambió un tipo, se re-resuelve todo el proyecto.
- La búsqueda de reparaciones prueba estados con snapshot y restore del texto, y consulta un grafo sin índices.
- El programa vive tres veces en memoria: los AST de JavaParser (de 90 a 160 veces el tamaño de las fuentes, y nunca
  se sueltan), el estado de ECJ y el grafo.

## 2. Para qué está la verificación

Podría parecer que la verificación cubre refactorings mal hechos. No es así:

- **Garantiza el resultado sobre este código.** Un refactoring probado funciona en los casos que alguien pensó. La
  verificación compara el antes y el después de este cambio sobre este código: capturas de nombres, una sobrecarga
  que pasa a elegirse distinto, un override accidental.
- **Permite refactorings simples.** El rename de IntelliJ son miles de líneas de casos especiales;
  `encapsulate-field` son 13 líneas de `.ref` que no se cuidan de nada.
- **Dispara las reparaciones.** Dice qué se rompió, y el conocimiento deduce cómo arreglarlo.
- **Juzga cambios que no son refactorings:** lo que edita un agente, un quick fix, un intent, una edición a mano.
  Para la IA es lo más valioso.

Su costo es el precio de una garantía que IntelliJ no da. Lo que hay que lograr es que sea barata, no eliminarla.

## 3. Localidad

Lo que importa no es el tamaño del proyecto sino **el alcance del cambio**: las referencias cuya respuesta puede
cambiar. En un rename de `m` a `n` son los usos de `m` y los usos del nombre `n` en los alcances donde los dos se ven.
Las reglas pueden calcular ese conjunto de forma conservadora, que es mucho más fácil que decidir cada caso, y la
verificación corre solo sobre él.

IntelliJ también es local: busca en su índice de palabras los archivos que contienen el nombre y resuelve esos
candidatos. Entonces la localidad nos empareja; lo que puede ponernos por delante es la precisión:

| Caso | Frente a IntelliJ | Por qué |
|---|---|---|
| Refactoring sobre un nombre común (`get`, `size`, `id`) | claramente mejor | referencias exactas por id contra candidatos por texto |
| Refactoring sobre un nombre único | parecido | los dos son locales |
| Consultas globales, trabajo de un agente | mucho mejor | el grafo ya está resuelto; IntelliJ resuelve al preguntar |
| Refactoring compuesto | mejor | una transacción y una verificación, no una búsqueda por paso |
| Mientras se escribe | igual | parsear y resolver el archivo cuesta lo mismo |
| Primera apertura | peor | resolver todo de entrada contra indexar perezoso |
| Aperturas siguientes | parecido | grafo guardado contra índice guardado |

Son estimaciones razonadas. El benchmark de la sección 9 las tiene que confirmar o corregir.

**Por qué IntelliJ no hace esto.** El texto es la verdad del ecosistema (git, merges, cambios de rama, código
generado, otros editores), y mantener un grafo con identidad ante cualquier cambio de texto es difícil. Además
IntelliJ abre monorepos de millones de líneas, lo que exige resolver de forma perezosa. Y ya se probó: VisualAge for
Java guardaba el código en un repositorio y Eclipse volvió a los archivos; JetBrains MPS es "el modelo es la verdad" y
quedó como nicho. IntelliJ elige velocidad y no verifica. IdeSeed elige garantías, y por eso tiene que ser rápido donde
el grafo ayuda, no en todo.

## 4. La idea: el grafo como estado de trabajo

1. Leer: el texto se parsea y se vuelve grafo. Es lo único que va del texto al grafo, y pasa al abrir o tras una
   edición a mano.
2. Los primitivos cambian **solo el grafo**, como ya hace `MemoryProjection`.
3. Las reglas calculan el **alcance** de cada cambio.
4. Las preguntas del alcance se **resuelven de nuevo sobre el grafo**. El verificador compara, y el conocimiento
   repara ahí. Un intento descartado nunca toca un archivo.
5. Al confirmar, el driver **escribe el texto una sola vez**, como ediciones mínimas.
6. ECJ compila los archivos tocados como **red final**. Si discrepa con el grafo, se deshace todo y la diferencia
   queda registrada. Ningún chequeo se apaga.
7. El grafo se **guarda en `.ide`**: al abrir se releen solo los archivos que cambiaron, y los AST son transitorios.

### Guardar la pregunta, no la respuesta

Hoy cada referencia guarda **a qué resuelve** (`target`), y por eso después de un cambio solo se puede saber si la
respuesta sigue siendo la misma preguntándole de nuevo a Java, sobre el texto. Para resolver sobre el grafo, cada
referencia tiene que guardar **su pregunta**:

- el nombre escrito;
- qué búsqueda es: nombre simple, miembro sobre un receptor, llamada con argumentos, `new`, nombre de tipo, `super`;
- desde dónde se busca: la sentencia, el método, el tipo, el archivo con sus imports;
- sobre qué se busca, si es una cadena: `getShop().total` es "`total`, buscado en el tipo que devuelve la referencia
  r6".

Con preguntas encadenadas, **leer es puramente sintáctico**: el lector no necesita resolver nada. La resolución y el
tipado son reglas que recorren las cadenas sobre el grafo, y cuando algo cambia se recalcula solo lo que depende de
ello.

### Qué queda propio de cada lenguaje

La semántica de Java no desaparece: pasa de código a **datos**, que son reglas sobre el grafo, como ya ocurre con el
acceso, los constructores y las excepciones en `java.rules`. Esto respeta los principios 1 (el núcleo no conoce Java)
y 7 (el conocimiento es declarativo).

| Pieza | ¿Propia del lenguaje? | Forma |
|---|---|---|
| Leer: parsear y armar el grafo con preguntas | sí | código, sintáctico |
| Escribir: cada cambio como edición mínima | sí | código, sintaxis |
| Resolución y tipado | sí | **reglas** |
| Reglas del lenguaje: acceso, constructores, herencia... | sí | reglas (ya existen) |
| Red final: compilador y build | sí | adaptadores chicos (ECJ, Maven) |
| Grafo, primitivos, motor, verificador, planificador, catálogo, intents, sesión, adaptadores | no | código común |

El código propio de Java queda muy chico. Escribir la resolución como reglas es el trabajo grande:

- Lo común (alcances, miembros, herencia, imports, sobrecarga simple) es razonable.
- La inferencia de genéricos (JLS capítulo 18) es compleja. Mientras no esté, el driver resuelve esos casos, pero solo
  dentro del alcance y contando cada uno, para completar las reglas con casos reales.

Un lenguaje nuevo pasaría a ser sobre todo un lector, un escritor y sus reglas. Para C#, Roslyn ya da el lector y el
escritor con el texto exacto conservado. El grafo tendría que crecer (propiedades, clases `partial` en varios
archivos, métodos de extensión, namespaces que no dependen de la carpeta), y un segundo lenguaje sería la mejor
prueba de dónde se coló Java en el núcleo. Conviene hacerlo después de esta etapa, no antes.

## 5. Ejemplo completo: extract superclass

### El código de partida

```java
// shop/Order.java
package shop;

import base.Entity;

public class Order extends Entity<Long> {
    private final int limit;
    private String note;

    public Order(int max) {
        super();
        this.limit = max * 2;
    }

    int room(int used) {
        return limit - used;
    }
}
```

Pedido: `extract-superclass type=shop.Order name=AbstractOrder fields=limit,note`.

### 5.1 El lector (de Java, sintáctico)

Parsea y arma el grafo sin resolver nada.

```
file   Order.java: package shop; import base.Entity
class  Order       parent shop          public
field  limit       parent Order         private final  type=q1
field  note        parent Order         private        type=q2
ctor   Order(int)  parent Order         public
param  max         parent Order(int)    type=q3
stmt   s1          parent Order(int)    "super()"
stmt   s2          parent Order(int)    "this.limit = max * 2"
method room(int)   parent Order         type=q4
param  used        parent room(int)     type=q5
stmt   s3          parent room(int)     "return limit - used"
```

Las preguntas: qué busca cada uso y desde dónde, no a qué resuelve.

```
q0  type        "Entity" args ["Long"]   from file Order.java        (extends de Order)
q1  type        "int"                    (primitivo)
q2  type        "String"                 from file Order.java
r1  super-ctor  args []                  from s1
r2  write       member "limit"  on this  from s2
r3  read        simple "max"             from s2
r4  read        simple "limit"           from s3
r5  read        simple "used"            from s3
fragment  s2.value = "max * 2"  with [r3]
```

Las expresiones siguen opacas (decisión 32). `max * 2` es un fragmento: su texto más las referencias que tiene
adentro. Se puede mover entero sin entenderlo.

**Hoy:** el lector existe (`ide.java.semantics.Reader`), pero resuelve con JavaParser y guarda respuestas.

### 5.2 La resolución de Java, como reglas (nueva)

Bocetos con la sintaxis de `java.rules`. Muestran la forma; no están probados.

```
# JLS 6.5.6.1: a simple name is a local declared before it, else a parameter, else a field of its type or of one it
# inherits.
def binds(q, x) when question(q, "simple", n, from) and local-before(x, from, n)
def binds(q, x) when question(q, "simple", n, from) and not local-before(_, from, n)
  and callable-of(from, c) and parameter-named(x, c, n)
def binds(q, x) when question(q, "simple", n, from) and not local-before(_, from, n)
  and callable-of(from, c) and not parameter-named(_, c, n) and type-of-site(from, t) and field-in(x, t, n)

# A field is found in a type, or in what it extends when the type does not declare one of that name.
def field-in(f, t, n) when member(f, t, n, "field")
def field-in(f, t, n) when not member(_, t, n, "field") and superclass(t, u) and field-in(f, u, n)

# A member on this: the same search, from the type of the site.
def binds(q, x) when question(q, "member", n, from) and on(q, "this") and type-of-site(from, t) and field-in(x, t, n)

# JLS 6.5.5: a type name is one of its file's single imports, else of its package.
def binds(q, t) when question(q, "type", n, file) and imported(file, t, n)
def binds(q, t) when question(q, "type", n, file) and not imported(_, file, n)
  and package-of(file, p) and member(t, p, n, _)

# super(...) calls the constructor of the superclass that takes those arguments.
def binds(q, k) when question(q, "super-ctor", _, from) and type-of-site(from, t)
  and superclass(t, u) and constructor(k, u) and takes-arguments(k, q)

# The type of a use, for chains such as getShop().total: the type of what it binds.
def type-of(q, ty) when binds(q, x) and type(x, ty)
```

Para este refactoring alcanza con nombres simples, campos heredados, imports y `super(...)`: una porción chica y bien
definida de la JLS.

### 5.3 Las reglas del lenguaje (existen en `java.rules`)

```
def in-between(b, t) when kind(t, "class") and same-superclass(b, t)

rule in-scope "a member used without saying whose is one of the type it is used in, or of one that type extends"
rule access "a member is used only where it can be"
def accessible(site, m) when package(site, p) and package(m, p) and may-be(m, "package") and visibility(m, "package")

rule final-where "a final field is given its value only by its own type's constructors and initializers"
  for s, f where writes(s, f) and final(f)
  must may-assign(s, f) or given-by-its-type(f, s)
end
def given-by-its-type(f, s) when ... constructor(k, u) and not takes-parameters(k) and parameter(p, k, n, v)
  and stores(p, k, f) and passes(p, s, f)
```

`stores`, `passes` y `same-superclass` se hacen verdaderas desde `Knowledge.java`, escribiendo en el lenguaje de
cuerpos, sin Java.

### 5.4 El intent (existe)

```
intent extract-superclass "Creates a class between a type and its superclass and pulls fields up into it"
  param type : element(CLASS) "the type to extract from"
  param name : text "name of the new superclass" default "Abstract${name(type)}"
  param fields : elements(FIELD) "fields to pull up" from "fields(type)"
  want given parent(type, p) and given not member(_, p, name, _) and member(b, p, name, "class")
    and in-between(b, type) and superclass(type, b) and each f in fields: parent(f, b)
end
```

### 5.5 El plan (el planificador existe)

| Paso | Primitivo | Por qué |
|---|---|---|
| 1 | `create CLASS shop AbstractOrder` | `member(b, p, name, "class")` |
| 2 | `link AbstractOrder extends q0'` (copia de `Entity<Long>`) | `in-between` |
| 3 | `retarget` del extends de `Order` a `AbstractOrder` | `superclass(type, b)` |
| 4 | `move limit AbstractOrder`, `move note AbstractOrder` | `each f in fields: parent(f, b)` |
| 5 | `set limit visibility package`, `set note visibility package` | se repara `access` |
| 6 | `create CONSTRUCTOR AbstractOrder` + `PARAM limit int` + `STATEMENT "#{limit} = #{p}"` | se repara `final-where` |
| 7 | `s1` pasa a `#{k}(fragment s2.value)` y `delete s2` | `passes` |

### 5.6 Los primitivos sobre el grafo (neutrales)

| Primitivo | Cambio de datos |
|---|---|
| `create` | se agrega un elemento |
| `move` | cambia el padre |
| `retarget` | cambia lo que pide una pregunta |
| `set` | cambia un hecho |
| `create STATEMENT` | se agrega una sentencia que **nace resuelta**: `#{limit}` ya es un id |

Lo nuevo es **mover un fragmento opaco**. `max * 2` pasa de `s2` a `s1` con su referencia `r3`, que sigue preguntando
`simple "max" from s1`. Como `s1` está en el mismo constructor, sigue encontrando el mismo parámetro.

**Hoy:** `MemoryProjection` aplica los primitivos así. El driver de Java los aplica sobre el texto.

### 5.7 La verificación en el grafo

El alcance, como reglas generales (bocetos):

```
def reach(q) when moved(f) and binds-before(q, f)                     # los usos de lo que se movió
def reach(q) when moved(f) and name(f, n) and asks-name(q, n)         # lo que podría capturar o ser capturado
def reach(q) when superclass-changed(t) and site-in-type(q, u) and subclass(u, t)
def reach(q) when created(x) and name(x, n) and asks-name(q, n)
```

Acá da `r1`, `r2`, `r4` y las preguntas del constructor nuevo: unas cinco, no el proyecto.

| Pregunta | Antes | Después | |
|---|---|---|---|
| `r4` (`limit` en `room`) | `limit` | no está en `Order`; sigue a `AbstractOrder` y lo encuentra: `limit` | mismo id |
| `r2` (`this.limit` en `s2`) | `limit` | `s2` se borró en el paso 7 | |
| `r1` (`super(...)`) | `Entity()` | `AbstractOrder(int)` | cambio declarado por el plan |
| acceso de `r4` a `limit` | private, misma clase | package, mismo paquete | regla `access` |

El dispatch no cambia, porque no se movieron métodos. **Hoy:** `BindingView`, `AccessView` y `DispatchView` ya
comparan dos grafos. Lo nuevo es el alcance y que la resolución sea sobre el grafo.

### 5.8 El escritor (de Java)

Archivo nuevo `shop/AbstractOrder.java`, en la raíz de fuentes de su paquete:

```java
package shop;

import base.Entity;                        // q0' resuelve a base.Entity: otro paquete, lleva import

public class AbstractOrder extends Entity<Long> {
    final int limit;                       // la declaración se mueve con su texto; se saca "private"
    String note;

    public AbstractOrder(int limit) {      // del lenguaje de cuerpos: #{limit} = #{p}
        this.limit = limit;                // "this." porque el parámetro tapa al campo
    }
}
```

Ediciones en `Order.java`:

- `extends Entity<Long>` → `extends AbstractOrder`;
- se borran las dos declaraciones de campos con sus líneas;
- `super();` → `super(max * 2);`, con el texto del fragmento;
- se borra la línea `this.limit = max * 2;`;
- `import base.Entity;` se borra, porque ninguna pregunta del archivo lo usa ya.

Todo lo que decide este paso es sintaxis Java: dónde va un miembro, la indentación, cuándo hace falta `this.`, los
imports, la carpeta de un archivo nuevo. **Hoy** este código existe (`ide.java.syntax` y la parte de texto de cada
primitivo); hay que separarlo para que escriba al final.

### 5.9 La red final

ECJ compila `Order.java` y `AbstractOrder.java`. Si aparece un error que antes no estaba, se deshace todo y la
diferencia entre el grafo y el compilador queda registrada.

### 5.10 Qué existe y qué falta

| Pieza | ¿De Java? | ¿Existe hoy? |
|---|---|---|
| Lector | sí | sí, pero guarda respuestas; tiene que guardar preguntas |
| Resolución y tipado | sí, como reglas | no; hoy la hace JavaParser |
| Reglas del lenguaje | sí, como reglas | sí, `java.rules` |
| Intent | no | sí |
| Planificador | no | sí |
| Primitivos sobre el grafo | no | sí en memoria; falta en Java, y mover fragmentos |
| Alcance | no | no |
| Verificador | no | sí, compara grafos |
| Escritor | sí | sí, mezclado en los primitivos |
| Red final | sí (ECJ) | sí |

## 6. Lo que no cambia

- Una edición a mano va del texto al grafo: parsear ese archivo, armar sus preguntas, reconciliar identidades.
- La primera apertura cuesta lo que cuesta resolver el proyecto. Con el grafo guardado, las siguientes no.
- La verificación nunca se apaga: se dirige y se acota.

## 7. Lo abierto

- **Qué tan completa empieza la resolución en reglas.** Lo que todavía no sabe decidir lo resuelve el driver, dentro
  del alcance y contado.
- **Performance del Datalog.** Resolver millones de preguntas exige evaluación incremental e índices.
- **Fragmentos opacos.** Tienen que conservar su texto hasta que se escriben, y un `Extract` o un `Inline` puede
  necesitar resolver el fragmento al escribir.
- **El grafo para otros lenguajes.** Propiedades, `partial`, métodos de extensión: cada uno es un caso del principio
  3, crecer el vocabulario solo cuando no se puede componer.

## 8. Las fases

Cada fase se mide contra el benchmark y deja todo verde.

0. **Benchmark y robustez.**
   - El benchmark escrito y medido en IntelliJ y en IdeSeed.
   - Un error interno de ECJ es un diagnóstico de su archivo, no impide abrir el proyecto. Hoy oozx no abre por eso.
   - El nivel de Java se toma del pom.
   - La búsqueda de reparaciones tiene un tope.
1. **Índices.** `GraphFacts` y `Program` indexados para las preguntas de las reglas. La lectura, perfilada y en
   paralelo.
2. **Alcance.** Se re-resuelven las referencias del alcance, no los archivos de todo el proyecto, también cuando cambia
   un tipo.
3. **Preguntas y resolución en reglas.** El lector guarda preguntas encadenadas. La resolución arranca por lo que
   necesita el verificador: nombres simples, miembros por tipo y herencia, tipos, imports, `super`. Extract superclass
   es el primer caso de punta a punta.
4. **El texto al final.** Los primitivos cambian solo el grafo, el escritor escribe al confirmar, ECJ compila una vez,
   y la búsqueda de reparaciones trabaja sobre estados del grafo.
5. **Grafo persistente.** El grafo en `.ide`, relectura de lo que cambió, AST transitorios.

## 9. El criterio: un benchmark contra IntelliJ

Las mismas operaciones, cronometradas en IntelliJ y en IdeSeed, sobre oozx y sobre IdeSeed:

- rename de un nombre común y de uno único;
- find usages;
- jerarquía de llamadas;
- un refactoring compuesto (extract superclass);
- apertura en frío y apertura con el grafo guardado.

Reemplaza a `PerformanceProbe`, que mide un sample sintético chico y no mostró nada de lo de la sección 1. Los
objetivos iniciales:

- oozx abre en menos de 5 s con el grafo guardado;
- un rename de un método con 300 usos tarda menos de 1 s;
- zx-lift completo entra en 2 GB.
