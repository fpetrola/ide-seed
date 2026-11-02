# Arquitectura de IdeSeed

Documento vivo. Describe los módulos del IDE como productos autónomos, el dominio de cada uno, las decisiones
tomadas y el plan para llegar desde el código actual. Se actualiza en el mismo commit que cambia una frontera.
El código y `CLAUDE.md` están en inglés; este documento, en castellano, porque es donde se piensa el diseño.

## 1. Qué construimos

IdeSeed es un IDE mínimo de Java donde el programa es un grafo con identidad estable y el texto es una proyección
de él. Un refactoring es una composición de pocos primitivos. La corrección no sale del cuidado con que se escribe
cada refactoring, sino de verificar el resultado contra el estado anterior y reparar o abortar. El conocimiento
para reparar y para los quick fixes es declarativo. Cada capacidad es un esquema más una invocación, y los
adaptadores (CLI, UI, MCP) solo traducen.

Lo que hay hoy funciona: 55 commits, 121 tests, 23 refactorings de catálogo, dos samples realistas. Pero creció sin
abstracciones internas: módulos cuyo código habla el dominio de otros, un objeto por capa que acumula todo, y
strings donde debería haber tipos. Este documento fija el diseño al que migramos y cómo.

## 2. El lente: cada módulo es un producto

La herramienta mental que ordena todo lo demás: cada módulo se piensa como si fuera un producto ajeno, una
librería que conseguimos en internet y que resuelve un problema que no está ligado a este IDE en particular. No
porque vayamos a publicarlo, sino porque esa pregunta ("si quisiera usar esto en otro proyecto, ¿qué tan autónomo
tendría que ser?") es la que produce dominios bien cortados.

De ahí salen tres reglas que se aplican en cada revisión de código:

- **Regla de autonomía.** Un módulo se puede bajar solo: se testea con una implementación falsa de lo que necesita
  de afuera, y su ficha se puede escribir sin mencionar al IDE.
- **Regla de vocabulario.** Un módulo se usa solo en los términos de su dominio. El código que coordina módulos se
  lee como una frase hecha con los términos de cada producto. Si para coordinar dos módulos hay que conocer el
  interior de uno, hay una intrusión.
- **Regla del dominio incompleto.** Cuando aparece una intrusión, el módulo con el dominio incompleto es el que
  tenía que resolverlo, y el arreglo va ahí, no en el coordinador. Ejemplo: si el motor necesita saber qué
  significa cambiar un superclass, al motor le falta el concepto "expectativa declarada por el cambio".

La consecuencia práctica más importante es que hace falta una proyección falsa en memoria: sin ella, el motor, los
refactorings y el conocimiento no pueden probarse solos, y la regla de autonomía queda en el papel.

La prueba de vocabulario, en el código que une todo:

```java
var tx = new Transaction(project, repairer);    // engine
Run.in(tx, library).call(refactoring, args);    // refactoring
var result = tx.commit();                       // engine
return record(tx, event, result);               // session: undo y log
```

Si para escribir eso hace falta más, a algún producto le falta un concepto. Es `Session.invoke` tal cual. Hasta la
fase 6 hacía falta algo de más: el reparador entraba dos veces, porque la transacción no sabía cómo se la juzga
(decisión 17).

## 3. Principios

Del producto (ya en `CLAUDE.md`, se mantienen):

1. El núcleo no conoce Java: la semántica del lenguaje vive en su proyección y en el conocimiento.
2. Una capacidad es esquema más invocación; los adaptadores no tienen lógica.
3. Primitivos mínimos, motor robusto: un primitivo nuevo solo si no se puede componer.
4. Ediciones mínimas y sin pérdida: formato y comentarios se conservan, los diffs son chicos.
5. Corrección por verificación: transformar ingenuamente, re-resolver, comparar, reparar o abortar; declarar lo que
   no se puede decidir en vez de adivinar.
6. Todo evento va al log append-only.
7. El conocimiento es declarativo; reparaciones y quick fixes se deducen de él.
8. No sobrediseñar.

De la arquitectura (nuevos):

9. Cada módulo es un producto con su dominio, y se usa solo en términos de ese dominio.
10. Las dependencias van en un solo sentido y las declara el pom de cada producto: el compilador las hace cumplir,
    y una dependencia nueva entre productos es una decisión visible en el diff. ArchUnit queda para lo que Maven no
    puede expresar.
11. Un producto se testea solo, con una implementación falsa de lo que necesita.
12. Tipos, no strings: todo lo que el motor compara o consulta tiene un tipo propio (ids, modos, hechos,
    direcciones, pasos del rastro). El texto aparece solo en la proyección y en los renders.

## 4. Mapa de módulos

```
          cli       ui  -->  mcp       (adaptadores: solo session y core; la ui sirve el mcp)
            \       |       /
                 acceptance            (solo tests: donde los productos se encuentran)
                     |
                  session              (ensambla)
          ___________|________________
         |           |            |   \
         |       knowledge        |    maven     (lo que Maven dice de un proyecto)
         |           :            |
         |         rules          |              (Datalog: no depende de nada; knowledge lo usa)
         |        /     \         |
       java   engine   refactoring
         \       |       /
          \______|______/
                 |
             projection
                 |
              program
                 |
               core
```

Cada producto es un módulo Maven: carpeta `<producto>/`, artefacto `ide-<producto>`, paquete `ide.<producto>`. Su
pom declara solo los productos que usa, y el pom es el mapa:

| Módulo      | Declara                                                    | Qué es                                       |
|-------------|------------------------------------------------------------|----------------------------------------------|
| core        | nada                                                       | kit de capacidades y utilidades              |
| program     | core                                                       | el programa como grafo                       |
| projection  | program                                                    | el contrato de un driver de lenguaje         |
| projection-kit | projection, JUnit, AssertJ                              | el contrato como tests y la proyección en memoria |
| java        | projection, JavaParser                                     | el driver de Java                            |
| engine      | projection                                                 | transacciones verificadas                    |
| refactoring | projection, engine                                         | refactorings, run, lenguaje `.ref`, catálogo |
| knowledge   | engine, refactoring, rules                                 | planificador por metas                       |
| rules       | nada                                                       | Datalog: reglas sobre hechos, un motor detrás de un contrato |
| maven       | nada                                                       | el classpath de un proyecto, según Maven     |
| session     | java, engine, refactoring, knowledge, maven; JavaParser en runtime | el ensamblado                         |
| acceptance  | session, solo para tests                                   | escenarios transversales, samples, reglas    |
| cli         | session, core                                              | adaptador: la terminal                       |
| ui          | session, core, mcp; RSyntaxTextArea, JediTerm, pty4j       | adaptador: la ventana Swing                  |
| mcp         | session, core                                              | adaptador: servidor MCP por stdio y HTTP     |

Cómo se hace cumplir:

- Un producto compila contra lo que declara y lo que está debajo de eso; nada de arriba ni de al lado.
- `refactoring` declara `engine` desde la fase 5: un run corre dentro de una transacción.
- Solo `java` compila contra JavaParser: `session` lo recibe en runtime, para ejecutar el driver.
- javac viene con el JDK, así que "solo `java` usa la API del compilador" lo verifica ArchUnit, igual que "solo
  `knowledge` implementa remedios". Son las dos reglas que Maven no puede expresar, y viven en `acceptance`.
- `acceptance` no es un producto: es donde los productos se encuentran.
- Un adaptador declara `session` y `core`, pero por la dependencia transitiva compilaría contra todo lo de abajo: su
  `AdapterTest` (ArchUnit) hace cumplir que use solo `session` y `core`.
- `projection-kit` es lo que bajaría quien escribe un driver: `java` lo declara para correr el contrato en sus
  tests; `engine`, `refactoring`, `knowledge` y `session` lo declaran para probarse sobre la proyección en memoria.
  Siempre solo para tests.

De dónde viene cada cosa:

| Hoy                                                                 | Mañana                                        |
|---------------------------------------------------------------------|-----------------------------------------------|
| `ide.core`                                                          | `core`, igual                                 |
| `ide.graph` Element, ElementId, Kind, Reference, ProgramGraph        | `program` (ProgramGraph pasa a llamarse Program) |
| `ide.graph.Program` (interfaz mutable), Diagnostic, CompletionContext, LanguageRules, PrimitiveRejected | `projection` (la interfaz pasa a llamarse Projection) |
| `ide.java.Identity`                                                 | `program.Identity`, con direcciones tipadas   |
| `ide.java` resto                                                    | `java`, con capas internas nuevas             |
| `ide.coherence`                                                     | `engine`                                      |
| `ide.refactor`, `ide.refactor.script`                               | `refactoring`                                 |
| `ide.knowledge`                                                     | `knowledge`                                   |
| `ide.workbench.Workspace`                                           | `session`: Session, Navigation, Completion, Capabilities, Workspace como fachada |

## 5. Fichas de producto

Cada ficha tiene la misma forma: qué es, para qué lo usarías en otro proyecto, su dominio (el vocabulario en el que
se le habla), qué ignora, su prueba de autonomía, su estado actual con las intrusiones conocidas, y lo abierto.

### 5.1 `core`: kit de capacidades y utilidades

- **Qué es.** `Capability`, `Param`, `CapabilityRegistry` (esquema más invocación con respuesta JSON), `Json`,
  `EventLog` (JSONL append-only), `TextDiff` (también lo que dos secuencias comparten en orden, `common`). Sin ningún
  dominio del IDE.
- **Prueba de autonomía.** Ya la pasa: se usa con lambdas en un test.
- **Estado actual.** Desde el ítem 6 de la etapa 2, `Json` también lee (la CLI escribe las respuestas como texto,
  MCP lee sus pedidos), y `Param` tiene el tipo `map` (nombres a valores). `JsonTest` es el primer test de `core`.

### 5.2 `program`: modelo de programas para herramientas

- **Qué es.** Una librería para representar un programa orientado a objetos como un grafo con identidad estable,
  consultarlo, comparar dos estados y describir cambios sobre él. No sabe de ningún lenguaje ni de texto.
- **Para qué lo usarías.** Un linter de dependencias, métricas, un visualizador de jerarquías, otra herramienta de
  refactoring sobre otro lenguaje.
- **Dominio.**
  - `Element`: `id`, `kind`, `name`, `parent`, `facts`. `Kind`: PACKAGE, CLASS, INTERFACE, ENUM, FIELD,
    CONSTRUCTOR, METHOD, PARAM, y desde la etapa 3 lo que hace un cuerpo (`inBody`): LOCAL (una variable de un método
    o constructor, hija de él) y STATEMENT (una sentencia del primer nivel de un cuerpo, hija de su método).
  - `Reference`: `site` (el miembro que hace el uso), `target`, `mode`, `name` (cómo se escribió), `statement` (la
    sentencia del cuerpo donde está, si está en uno) y `receiver`: sobre qué se hace el uso, el
    objeto mismo (escrito solo, o con `this` o `super`), un tipo, u otro objeto; un vínculo, un tipo usado, un
    objeto nuevo o un local, sobre nada. Lo agregó la regla de estáticos: un método estático usa lo que necesita un objeto solo a
    través de uno, y eso depende de cómo está escrito el uso (obligación 12 del contrato). Modos de uso: READ, WRITE, UPDATE, CALL, NEW,
    TYPE. Modos de vínculo: EXTENDS, IMPLEMENTS, DECLARED (el tipo declarado de un campo, parámetro o retorno). Un
    vínculo es una referencia porque la jerarquía y el verificador lo tratan igual que a un uso: hoy son strings
    duplicados en atributos y eso obliga a re-resolver nombres en todos lados.
  - `Unresolved`: un uso cuyo nombre no liga nada (site, nombre, modo, y `scope`: el tipo donde se busca un
    miembro, el del receptor o el que lo escribe; nulo para un tipo). Qué necesita de lo que lo ligaría no es parte
    del programa: se le pregunta a la proyección (`needs`, decisión 27). Su id está en el mismo espacio que el de
    las referencias (`site@ordinal`), así un primitivo puede nombrarlo. Vive aparte de las referencias, que siempre
    ligan algo: nada de lo que lee referencias tiene que preguntar si tienen destino.
  - Elementos de biblioteca: los tipos de una biblioteca que el workspace nombra, con su paquete, marcados con el
    hecho `LIBRARY`. El programa los nombra y nada los cambia; su identidad es su nombre. La jerarquía
    (`superclass`, `supertypes`, `declaredType`) es la del workspace; `link(element, mode)` responde un vínculo
    adonde sea que ligue.
  - `Facts`: conjunto cerrado y tipado: `visibility` (valores que da el lenguaje, ordenados), `static`, `abstract`,
    `final`. Se amplía solo cuando un refactoring o una vista lo necesita para decidir.
  - `Address`: dirección tipada. Paquete por nombre; tipo por nombre calificado; miembro por id del dueño, nombre y
    claves de tipo de los parámetros (el id si el tipo es del workspace, el nombre si no); parámetro por id del
    miembro y nombre; local por id de su método o constructor, nombre y cuál de ese nombre es, en el orden en que se
    escriben; sentencia por id de su método y su lugar en el cuerpo. Se renderiza como `a.B#m(int)/x` para humanos y
    tests, el segundo `i` de dos bloques hermanos como `a.B#m(int)/i'2`, y la primera sentencia como `a.B#m(int)[1]`. Consecuencia buscada: un miembro no cambia de dirección cuando su dueño o un tipo de parámetro
    se renombra o se mueve.
  - `Identity`: tabla dirección → id. `of(address)` crea si no existe; `rebind(type, newType)` para rename y move de
    tipos; `placeLocals(método, locales)`: los locales de un método, en el orden en que se escriben y con sus
    nombres, toman su número entre los de su nombre todos a la vez (uno que se mudó o se renombró, o los que se corren
    porque otro de su nombre vino o se fue); `placeStatements(método, sentencias)`, lo mismo para las sentencias, con
    lugares libres para las que la lectura dé nuevas; `forget`. Con instantánea. Es la única operación de identidad: el parser nunca decide quién es quién.
  - `Program`: el grafo inmutable, con índices por padre, por destino y por dirección. Consultas: children,
    members, referencesTo, referencesFrom, supertypes, superclassChain, isSubclass, overriddenAll, family,
    enclosingType, topLevel, byAddress, signature.
  - `Diff`: `Program.compare(before, after)`: referencias perdidas y ganadas (por site y target), elementos nacidos e
    idos, hechos cambiados. Es lo que consumen las vistas del motor.
  - Vocabulario de cambio. `Primitive`, un tipo sellado de records:
    - `Create(kind, parent, name, spec)`: `spec` trae hechos textuales iniciales (type, default para parámetros,
      body), así la creación es un paso y no dos con marcadores. Una sentencia se crea dicha en el lenguaje de
      cuerpos, al final del cuerpo, y hace los usos que nombra.
    - `Delete(element, into)`: sin `into`, borra lo que nada usa; con `into`, fusiona: un campo en un campo, un
      método en un método, y los usos del borrado pasan al otro.
    - `Move(element, parent, before)`: también reordena parámetros, y lleva una sentencia antes de otra o al final de
      un cuerpo, en su método o en otro; entre métodos, los usos hechos en ella pasan a ser del otro.
    - `Rename(element, name)`.
    - `Set(element, fact, value)`: hechos cerrados y hechos textuales (type textual, initializer). Hasta la fase 3 de
      la etapa 3 también el cuerpo entero de un método; desde entonces un cuerpo son sus sentencias, que se crean,
      mueven y borran.
    - `Link(element, mode, target)`: extends, implements, declared hacia un tipo del workspace. Reemplaza
      `set superclass`, `set implements` y `set type` con tipo del workspace.
    - `Retarget(reference, target)`.
    - `Extract(method, fragment, name)` e `Inline(reference)`: el fragmento son sentencias del cuerpo que se siguen,
      por sus ids (`statement:7,statement:8`), o una dirección opaca que entiende la proyección (en Java, líneas
      `primera-última` o el tramo exacto de una expresión). `Inline` reemplaza una llamada por el cuerpo del método, o la
      lectura de un local por el valor que se le da.
  - `Body`: lo que hace un método, en ningún lenguaje. Sentencias (`return`, asignar un campo) sobre expresiones (el
    valor de un campo o de un parámetro, un objeto nuevo), con cada elemento nombrado por id como `#{id}`. Es el
    cuerpo de un método: sus parámetros son valores del cuerpo, no usos entre elementos. `uses()` dice qué usos hace
    de lo que está fuera del método. Como `Fact`, crece solo cuando una plantilla necesita decir algo que no puede.
  - `Change`: lo que un primitivo devuelve. `produced` (el elemento creado, si hay), `expectations` y `pins`.
    `Expectation`: `Retargeted(site, from, to)`, `Resited(target, from, to)`, `Dropped(site, target)`,
    `Added(site, target, name, mode)`, `Bound(use, to)`, `UsesGone(element)`, `Merged(element, into)`. `Merged`
    saca el elemento de la línea base: sus usos ligan `into`, y lo que lo sobrescribía no es un override perdido.
    `Pin(element, aspect)` con aspecto
    parent, name o visibility: lo que el refactoring hizo a propósito y una reparación no puede deshacer.
- **Qué ignora.** Texto, posiciones, archivos, parser, compilador, y los cuerpos como los escribe un lenguaje: de un
  cuerpo sabe solo lo que dice `Body`.
- **Prueba de autonomía.** Un test arma un grafo con tres elementos y dos referencias y ejercita consultas, diff y
  direcciones, sin parser.
- **Estado actual** (fases 1 y 2 cerradas, 2026-10-31). El vocabulario de cambio existe: `Primitive` con `Create`
  (con hechos y argumento), `Delete`, `Move`, `Rename`, `Set` (texto), `Link`, `Retarget`, `Extract` e `Inline`;
  cada uno declara su intención (`Primitive.intent`) y se documenta a sí mismo (`@Summary`, `@Optional`);
  `Change` con expectativas y pins tipados; `Program.expecting` y `Program.outline`. Desde la fase 1: `ide.program`: `Mode` y `Fact` tipados; extends, implements y el
  tipo declarado son referencias; `Program` indexa por padre, site, destino y dirección, calcula `diff` y memoriza
  los overriders; `Address` e `Identity` tipadas, con las claves de tipo de parámetro por id; cada `Element` lleva
  su dirección, y la sobrescritura compara nombre y claves, no texto. Desde la fase 6 responde también la jerarquía
  entera: `ancestors`, `subtypes` (por el índice de referencias) y `overriddenTransitively`, que antes calculaba la
  navegación de la sesión recorriendo el grafo a mano. Desde la etapa 2 tiene usos sin resolver y elementos de
  biblioteca; `Retarget` liga un uso sin resolver (`Change.Bound`); `Primitive.changed()` dice qué elementos cambia
  cada primitivo; el outline muestra `site -> ? nombre (modo)` y marca los tipos `(library)`. También desde la etapa 2,
  `Reconciliation` empareja, después de una edición a mano, cada elemento nuevo con el de antes que es (renombrado,
  con otra firma o movido de paquete), solo cuando la estructura no deja dudas: el tipo de un campo, el nombre o el
  cuerpo de un método, el lugar de un parámetro, lo que contiene un tipo, o ser el único de su clase que se fue de un
  padre mientras llegaba otro. `Identity.transfer` le da al id viejo la dirección nueva. Desde el ítem 4 de la
  etapa 2 el vocabulario de cambio dice cuerpos: `Body`, y un `Set` de cuerpo declara que los usos del cuerpo viejo se
  van (`Dropped`) y que el método hace los que el nuevo nombra (`Added`). Desde el ítem 5, `Delete` con `into` fusiona
  un miembro en otro (`Merged`), y `within(element, container)` dice si un elemento está dentro de otro.
- **Abierto.** Claves de tipo con genéricos anidados (hoy el texto descrito con los ids sustituidos); constantes de
  enum (no se modelan); sentencias y variables locales como elementos: propuesto en la decisión 32 (sección 9,
  etapa 3), porque cada pasito que un refactoring da dentro de un cuerpo hoy pide vocabulario nuevo.

### 5.3 `projection`: el driver de lenguaje

- **Qué es.** El contrato que un lenguaje implementa para que todo lo de arriba funcione con él. No tiene lógica
  propia: interfaces, tipos de intercambio y el kit de conformidad. Como JDBC: las herramientas se escriben contra
  el contrato, cada lenguaje trae su driver. Define cuánto cuesta un lenguaje nuevo, y por eso merece ser visible.
- **Dominio.** La interfaz `Projection`:
  - `Program program()`: el grafo actual, con identidad.
  - `Change apply(Primitive)`: ejecuta el primitivo en el lenguaje y declara expectativas y pins. Rechaza con
    `PrimitiveRejected(code, detail)` y sin efecto.
  - `Snapshot snapshot()` y `restore(Snapshot)`: todo el estado, texto e identidad, tipado.
  - `edit(path, text)`: edición manual; el texto del usuario es la autoridad; rechaza lo que no parsea.
  - `List<Diagnostic> diagnostics()`: la vista del compilador.
  - `Optional<CompletionContext> completion(path, text, offset)`.
  - `Optional<Location> locate(element)` y `Optional<ElementId> elementAt(path, line, column)`: dónde está declarado un
    elemento, y qué elemento declara o usa el nombre en una posición. Lo que un editor necesita para F3, un menú
    contextual o un diálogo precargado con lo seleccionado.
  - `Optional<Needs> needs(use)`: qué necesita un uso sin resolver de lo que lo ligaría, como el lenguaje escribe los
    tipos: el tipo que su lugar espera de su valor y, de una llamada, el nombre y el tipo de cada argumento. Se
    pregunta por un uso, cuando un fix lo necesita; no se calcula al leer ni se guarda en el grafo.
  - `LanguageRules rules()`: accesibilidad, visibilidades posibles de un elemento, y cómo escribe el lenguaje el
    valor que tiene un campo que nadie asignó. Ya no ofrece candidatos de import: el programa tiene los tipos que un
    uso podría nombrar.
- **Obligaciones del driver**, que el kit de conformidad (un test abstracto que corre contra cada implementación)
  verifica:
  1. Identidad: un elemento conserva su id a través de rename, move, set, link y retarget, también cuando eso le
     cambia la dirección (un método cuyo parámetro pasa a declararse con otro tipo); lo creado recibe un id nuevo; lo
     borrado desaparece y no vuelve.
  2. Cada primitivo declara exactamente lo que cambió a propósito: su `Change` es la única fuente de expectativas.
  3. Lectura reproducible: `program()` después de un `apply` es igual al de una proyección nueva sobre el mismo
     texto, salvo los ids.
  4. `snapshot` y `restore` devuelven texto, identidad y grafo idénticos.
  5. Un primitivo rechazado no deja ningún cambio.
  6. Un elemento de biblioteca nunca cambia: todo primitivo que lo cambiaría se rechaza (`projection.library`, la
     regla vive en `PrimitiveRejected.unlessInWorkspace` y la usan los dos drivers). Lo mismo para fusionar lo que no
     es de la misma clase (`delete.into`, en `PrimitiveRejected.unlessMergeable`).
  7. Un retarget de un uso sin resolver lo liga: desde ahí es una referencia.
  8. Un cuerpo dicho sin lenguaje (`Body`) se escribe en el del driver, y el método hace los usos que el cuerpo
     nombra, en ese orden. Sus parámetros son valores, no usos.
  9. Lo que una proyección ubica (`locate`: archivo, línea y columna del nombre de una declaración) se encuentra donde
     dice (`elementAt`). Una proyección sin texto no ubica nada.
  10. Un uso de un miembro que no liga nada dice en qué tipo se busca, y la proyección dice, si se le pregunta, qué
      necesita (`needs`); de cualquier otra cosa, nada.
- **El kit, `projection-kit`.** `ProjectionContract` son las obligaciones como tests: cada driver lo extiende y da
  el mismo programa de ejemplo en su lenguaje. `MemoryProjection` es una proyección sin lenguaje sobre un grafo
  mutable: aplica los primitivos al grafo y declara los mismos `Change`. Es la implementación de referencia y lo
  que permite probar `engine`, `refactoring` y `knowledge` solos. Trae dos reglas de lenguaje: `OPEN`, donde nada
  está restringido, y `PRIVATE_TO_TOP_LEVEL`, donde un privado se accede solo desde su tipo de nivel superior.
- **Qué ignora.** Refactorings, transacciones, conocimiento, sesión.
- **Estado actual** (fase 2 cerrada, 2026-10-31). `Projection` es `program`, `apply`, `edit`, `snapshot` y `restore`
  tipados, `rules`, `diagnostics` y `completionContext`. El texto (`files`, `sources`) queda en el driver de Java:
  lo usa la sesión, que lo conoce. El contrato verifica las cinco obligaciones más los parámetros (se crean con el
  argumento que pasan las llamadas y se mueven dentro de su método). Corre contra `MemoryProjection` y contra el
  driver de Java, donde encontró un bug: crear un campo con un nombre existente no se rechazaba. En la etapa 2 el
  fixture nombra `String` (de la biblioteca) y `Money` (que no existe), y suma las obligaciones 6 y 7. El ítem 4 suma
  la obligación 8 y dos escenarios para las obligaciones 2 y 3: escribir un cuerpo, y reemplazar uno que tenía usos.
  La obligación 8 pedía también el cuerpo que empieza con `...`, que conservaba lo que el método hacía y agregaba lo
  que seguía; desde la fase 3 de la etapa 3 eso es crear una sentencia al final del cuerpo (obligación 14).
  La obligación 11 (`said`), desde la fase 3 de la etapa 3 también por sentencia: la proyección dice el cuerpo de un
  método o constructor, o una sentencia, en el lenguaje de cuerpos cuando todo se puede decir así (asignar un campo, responder un valor, hacer el objeto con otro constructor primero, sobre
  campos, parámetros, llamadas y objetos nuevos); si algo no se puede (un local, un literal), no dice nada.
  La proyección en memoria guarda un cuerpo como su texto y hace los usos que nombra. La obligación 13 (etapa 3,
  fase 1): un local es un elemento de su método, con su tipo, y cada uso suyo es una referencia desde el método,
  sin receptor. La obligación 14 (fase 2): las sentencias de un cuerpo son elementos de su método, en orden, y un uso
  hecho en una dice en cuál está; un cuerpo que conserva lo que el método hace agrega sentencias después de las
  suyas, que siguen siendo quienes eran, y uno que lo reemplaza trae sentencias nuevas. El ítem 5 suma al fixture una
  clase anidada sin usos (`b.C.Note`) y tres escenarios: borrarla, fusionar un método en otro, y retargetear el tipo
  de un parámetro; este último encontró que los dos drivers perdían la identidad del método.
  La obligación 15 (2026-11-02): un método o constructor dice qué declara tirar, como está escrito (`THROWS`), y un
  `Set throws` declara los tipos dados, cada uno escrito como su archivo puede nombrarlo (Java importa el calificado).
- **Abierto.** Si `diagnostics` y `completion` quedan en el mismo contrato o en uno de análisis aparte: juntos
  hasta que estorbe.

### 5.4 `java`: editor estructural de Java

- **Qué es.** Una librería que, dado un conjunto de fuentes Java en memoria, lee el programa como grafo y ejecuta
  primitivos como ediciones mínimas de texto, conservando formato y comentarios. La única que conoce JavaParser y
  el compilador de Eclipse (ECJ).
- **Para qué lo usarías.** Codemods, migraciones masivas, cualquier automatización sobre un codebase Java, sin
  verificación ni scripts.
- **Dominio**, en capas internas, cada una un paquete con su vocabulario; `LayersTest` hace cumplir que cada una
  conozca solo las de abajo:
  1. Texto, `ide.java.text`. `Unit` (un archivo: ruta, texto y el árbol leído de ese texto; los offsets viven solo
     acá), `EditSet` (ediciones hechas sobre el texto como estaba, aplicadas de atrás hacia adelante), `Sources`
     (los archivos cambian solo como texto, todo o nada, se reparsean solo los tocados; el único lugar que parsea).
     Es la única vía de cambio: no hay mutación del árbol ni printer léxico.
  2. Sintaxis, `ide.java.syntax`. `Decl` (dónde está declarado un elemento: nodo y unidad), `Members` (dónde va un
     miembro, cómo se va, su texto con el comentario), `Modifiers`, `Lists` (parámetros y argumentos), `Imports`,
     `Render` (texto de código nuevo).
  3. Semántica, `ide.java.semantics`. `Reader` (la lectura: programa, nodo de cada uso, declaración de cada
     elemento), `Resolution`, `Keys`, `WorkspaceTypeSolver`, `EcjCheck`, `Bodies` (flujo de datos y precedencia
     para extract e inline), `Completion`.
  4. Primitivos, `ide.java.primitives`. Un `Context` (el programa leído, las declaraciones, las fuentes, la
     identidad) y una clase por primitivo, todas con la misma forma: precondiciones, ediciones, transferencia de
     identidad, `Change` declarado.
  5. Fachada, `ide.java.JavaProjection`: lo que las capas comparten (fuentes, identidad, lectura) y lo que cada
     cambio vuelve viejo. Unas 200 líneas, 45 de ellas el contexto que ven los primitivos.
- **Qué ignora.** Transacciones, verificación, refactorings, scripts, sesión.
- **Prueba de autonomía.** Aplicar un primitivo sobre texto y comparar texto; el kit de conformidad de `projection`.
- **Estado actual** (fase 3 cerrada, 2026-10-31). Las cinco capas existen y ningún archivo pasa de 275 líneas.
  Cada capa tiene tests propios: `SourcesTest` (texto), `SyntaxTest` (sintaxis), el contrato (el driver entero) y
  `LayersTest`. Un miembro movido lleva su comentario. Un texto que no parsea se rechaza como
  `projection.unparsable`, en vez de romper. `set` todavía tiene un caso que solo entiende Java: `initializer`, el
  valor inicial como se escribe, que viene del usuario o de las reglas (`JavaRules.defaultValue`). Desde
  la etapa 2 el Reader suma los tipos de biblioteca que el código nombra (con id `lib:` y su nombre, fuera de la
  tabla de identidad, donde cambiarían las claves de los miembros) y los tipos escritos que no ligan nada, con los
  tipos del JDK que podrían nombrar. Ligar uno escribe el nombre del destino y lo importa si hace falta. Reemplazar
  un tipo vinculado declara que los usos dentro de él, como sus argumentos de tipo, se van con él. Una edición a mano
  reconcilia la identidad por rondas (tipos, lo que contienen, parámetros), leyendo de nuevo entre una y otra; lo
  que la edición quitó se olvida, para que su id no vuelva con otra cosa. La lectura después de un cambio conserva lo
  que el cambio no pudo afectar: un archivo cambiado se lee de nuevo; los usos de otro archivo se resuelven de nuevo
  solo si pueden ligar distinto (todos, cuando cambió un tipo; si no, los que se llaman como un miembro cuya
  declaración cambió y ligaban un miembro de su tipo, de un supertipo o de un subtipo, o están dentro de su tipo o de
  un subtipo, o no ligaban nada, o ligaban algo que el programa no sigue). La forma de lo declarado se lee del texto
  (`syntax.Shape`); `resolvedLastTime()` dice qué archivos resolvió la última lectura. Desde el ítem 4 escribe en Java
  los cuerpos dichos sin lenguaje (`primitives.WriteBody`, con las formas de Java en `syntax.Render`): un campo o un
  parámetro por su nombre, un campo a través de `this` (o de su tipo, si es estático) cuando un parámetro del mismo
  nombre lo tapa, y un objeto nuevo por el nombre simple de su tipo, importado si hace falta. Desde el ítem 5 borra
  tipos anidados (sus líneas, como un miembro); fusiona un miembro en otro renombrando los usos que lo nombran
  distinto en la misma edición que lo quita, así ningún uso liga otra cosa en el medio; un retarget del tipo escrito
  en un parámetro le lleva la identidad al método; y un archivo nombra sin import los tipos que declara. Desde el ítem
  6 responde posiciones desde su lectura (`semantics.Positions`): un uso se encuentra por el nombre con que está
  escrito, así el cursor sobre `a` en `a.m(1)` está en `a` y no en la llamada. Lee Java hasta 21 (antes leía al nivel
  por defecto de JavaParser y un proyecto con un `instanceof` con patrón no abría): un record es una clase y un tipo
  anotación una interfaz, para el lector y para el type solver del workspace; un tipo declarado dentro de un método es
  del método, como sus variables locales, y no un tipo del programa. Sus bibliotecas son el JDK y los jars
  del classpath que le dan (`JavaProjection.of(sources, classpath)`): los tipos de un jar ligan como los del JDK, leídos
  sin cargar una clase, y el compilador compila siempre contra ese classpath; antes, sin classpath, javac leía el del
  IDE y el código analizado compilaba contra JavaParser y Guava aunque su proyecto no los tuviera.
  Desde el 2026-11-01 compila con ECJ, incremental como el builder de Eclipse (`semantics.EcjCheck`, decisión 28):
  errores y warnings con línea, columna, largo y lo que el compilador nombra en ellos; un texto que el editor dice y
  el programa no, se compila sin tocar el programa (`diagnostics(path, text)`). Un campo, una llamada o un `new` que
  no ligan nada son usos sin resolver cuando el resolvedor lo asegura, y lo que cada uno necesita lo responde
  `semantics.UseNeeds` cuando se lo preguntan. Responde también en qué elemento cae una posición (`inside`) y qué
  construcciones la rodean (`around`, para agrandar la selección). Crea constructores, y extract toma, además de
  líneas enteras, el tramo exacto de una expresión (`línea:columna-línea:columna`) o un fragmento que termina el
  método con su return. Desde la fase 1 de la etapa 3 lee los locales de cada método y constructor (no los de una
  lambda ni los de una clase de adentro) y liga sus usos; `JavaRules` dice que a un local se llega solo desde adentro
  de su método. Extract dice qué usos de locales se van: los que en el método nuevo son parámetros, y los del local
  que responde, que en el método nuevo es otro. Los locales declarados solo en el fragmento se mudan con su
  identidad, y los que quedan se numeran de nuevo. El flujo de extract distingue dos locales del mismo nombre por lo
  que liga cada uso: antes, extraer un `for` se rechazaba si un `for` siguiente reusaba su `i`. Un local se renombra
  con sus usos, y puede tomar el nombre de uno de un bloque hermano: los locales del método se numeran de nuevo.
  `Inline` de la lectura de un local la reemplaza por su valor (`InlineLocal`), entre paréntesis si hace falta, y
  dice que los usos del valor se hacen ahí. Rechaza lo que cambiaría lo que hace el programa sin cambiar a qué liga un
  nombre, que el verificador no ve: un local al que se le da otro valor después, un valor con efectos o de otro tipo
  que el local, uno que se leería después de que cambie lo que lee (una escritura en el medio, una llamada si lee un
  campo, la vuelta siguiente de un ciclo), y una lectura que pasa más tarde, en una lambda o una clase. Un local que
  nada lee se borra con sus líneas, si su valor no tiene efectos. Desde la fase 2 lee las sentencias del primer nivel
  de cada cuerpo; los primitivos que cambian esa lista les conservan el id, y una edición a mano también, por orden y
  texto. Borrar un parámetro saca su argumento de cada llamada, y declara que los usos que ese argumento hacía se van
  a propósito (antes el verificador los tomaba por ligaduras perdidas); un argumento con efectos se rechaza
  (`delete.effects`), porque dejarían de pasar.
- **Abierto.** Los componentes de un record no son elementos, ni sus accesores implícitos. Todo
  el programa vive en memoria, con el árbol de cada archivo: un proyecto de 38 MB de fuentes no entra en 4 GB (un
  módulo de 280 archivos se lee y compila en unos 3,5 s). Un campo de la clase externa que una interna tapa no se escribe `Outer.this.x`: el motor rechaza el
  cuerpo (`binding.rebound`).
  Un miembro llevado a mano de un tipo a otro, o de un archivo a otro, no se reconoce: cada edición es de
  un archivo y la reconciliación empareja dentro de un mismo padre. Renombrar un parámetro no renombra sus usos en el
  cuerpo, porque esos usos no son referencias del grafo.

### 5.5 `engine`: motor de transacciones verificadas

- **Qué es.** Una librería que abre una transacción sobre una proyección, deja aplicar cambios, compara el estado
  de después con el de antes según vistas enchufables, busca reparaciones entre remedios dados, y cierra con un
  resultado explicable o deshace. No sabe qué es un refactoring, un lenguaje ni una regla concreta.
- **Para qué lo usarías.** Cualquier transformación que deba preservar invariantes y explicar qué pasó: migraciones
  de esquema, cambios de configuración.
- **Dominio.**
  - `Transaction`: nace con la proyección y el reparador que la juzga. Guarda la línea base (el `Program` de antes
    con las expectativas plegadas), los pins, el rastro y el punto inicial. Intercepta `apply(Primitive)`: delega a
    la proyección, pliega las expectativas del `Change`, acumula pins, registra el paso. No interpreta primitivos.
    Un punto (`snapshot`) guarda también la línea base y los pins: volver a él olvida lo declarado desde entonces.
    `attempt(label, body)` corre parte del cambio como intento: si lo rechazan o no verifica, se deshace y queda en
    el rastro como descartado. `commit()` verifica y repara; si no llega a un punto fijo, deshace todo.
  - `View`: `check(baseline, after, rules)` devuelve violaciones. Vistas: Binding (perdida, religada, sin resolver),
    Access, Dispatch (overrides perdidos o nuevos), Compiler (todo error que no estaba antes, aunque el código no
    compilara: mismo archivo, código y mensaje, cualquier línea, tantos como había; corre última porque es la más
    cara; decisión 30). Futuras: Projection (direcciones duplicadas), Init.
  - `Verifier`: una lista de vistas más la exención de lo que ya estaba roto antes de la transacción.
  - `Violation`: código estable `vista.tipo`, site, target, hechos para los remedios.
  - `Remedy` y `QuickFix`: proponen `Plan`s para una violación o un diagnóstico, sobre la proyección, que trae sus
    reglas. `Plan`: título, preguntas (asks), acción sobre la proyección.
  - `Repairer`: toma la primera violación, prueba planes sin preguntas del más barato al más caro desde un punto
    guardado; conserva el que hace desaparecer esa violación sin mover un pin; topes de 10 rondas y 16 ramas. Solo
    toma planes que conservan el sentido (decisión 31), y lo que declaran sus primitivas se pliega al programa esperado
    como el de un cambio (una reparación que reescribe un cuerpo no se juzga como un binding perdido).
  - `Trace`: árbol de pasos con tipo (call, primitive, question, answer, dropped, assumed, note), etiqueta e hijos.
    Un único render a líneas con sigilos (`.`, `'`, `?`, `!`, `X`, `:`).
  - `Result`: aplicado o abortado, violaciones, reparaciones, rastro, obligaciones y garantía (`guaranteed` o
    `assumed`).
- **Qué ignora.** Lenguajes, scripts, conocimiento, sesión.
- **Prueba de autonomía.** Sobre `MemoryProjection`, con una vista y un remedio escritos en el test: llega al punto
  fijo, respeta los topes, no mueve pins, deshace cuando no puede.
- **Estado actual** (fase 6 cerrada, 2026-10-31). `Transaction` intercepta `apply`, pliega el `Change` declarado y
  acumula pins tipados; no conoce ningún atributo. Las vistas son una clase cada una (`BindingView`, `AccessView`,
  `DispatchView`, `CompilerView`) detrás de `View` y `Comparison`; `Verifier` es una lista de vistas, y las costosas
  corren solo si las baratas no ven nada. La exención de lo que ya estaba roto vive en la vista que la necesita
  (acceso). `Trace` es un árbol de datos y el único lugar que escribe sigilos; el planificador de `knowledge` también
  responde su deducción como `Trace`. Tests propios sobre la proyección en memoria: transacción, reparador, rastro y
  cada vista sola. En la fase 5 el intento pasó de la sesión a la transacción, y remedios y quick fixes dejaron de
  recibir las reglas aparte de la proyección. En la fase 6 la transacción nació con su reparador (decisión 17). En
  la etapa 2 el paso de una edición a mano dice qué se entendió de ella: cada elemento que conserva su id con otra
  dirección (`a.P#x -> a.P#count`). Desde el ítem 4 la línea base sabe qué usos hace un cuerpo nuevo (`Added`), y la
  vista de ligaduras ve si lo que escribió el lenguaje liga otra cosa (`binding.rebound`) o nada; nombra también un
  destino nacido con el cambio. Desde el ítem 5 la vista de despacho no reporta un override que ninguna llamada del
  método sobrescrito puede alcanzar: las llamadas de un método subido se hacían sobre el tipo de donde vino, y un
  hermano fuera de esa jerarquía nunca corre para ellas (decisión 21).

### 5.6 `refactoring`: lenguaje de refactorings

- **Qué es.** Una biblioteca de refactorings, un run que los ejecuta, y el lenguaje `.ref` para escribirlos como
  composición de primitivos y de otros refactorings.
- **Para qué lo usarías.** Para que usuarios o equipos definan sus propios refactorings sin escribir Java.
- **Dominio.**
  - `Refactoring`: id, descripción, parámetros, nivel (primitive, script, composite), `apply(Run, Args)`, que
    responde el elemento que produjo o sobre el que trabajó.
  - `Library`: registro y búsqueda. Los primitivos se exponen como refactorings mecánicamente desde los records de
    `Primitive`; `define(text)` agrega scripts.
  - `Run`: lo que un refactoring ve mientras corre: `program()`, `rules()`, `call(id, args)`, `attempt(label, body)`,
    `oblige(assumption)`. Es uno solo y corre en dos lugares:
    - Dentro de una transacción (`Run.in`, lo que hace la sesión): las llamadas se anidan en el rastro, un intento
      rechazado o que no verifica se deshace, y una suposición es una obligación del resultado.
    - Directo sobre una proyección (`Run.on`: un plan, o un script bajo test): no hace falta el motor. Un intento
      se deshace si lo rechazan, y suponer se rechaza, porque una reparación no puede suponer lo que no verifica.
  - `Value`: lo que los refactorings se pasan y los scripts calculan: `Elem` y `Ref` (por id), `Text`, `Bool` e
    `Items`. La ambigüedad "¿es un id o una dirección?" se resuelve una vez, en `Args`. Dentro de un script, un
    texto donde se espera un elemento es una dirección: los ids no se escriben a mano.
  - El lenguaje: `ScriptParser`, `Stmt` (let, call, for, if, try, require, oblige, fail, return), `Expr` (literal,
    variable, función, filtro) y `ScriptFunctions` (consultas sobre el programa y las reglas). En un texto, `${expr}`
    escribe un valor (un elemento, por su id) y `#{expr}` escribe elementos como los nombra un cuerpo. Quien embebe
    scripts, como los efectos de `knowledge`, le pregunta al lenguaje dónde termina un bloque
    (`ScriptParser.blockLength`).
  - El catálogo `standard.ref`, con presupuesto de 20 sentencias por script y sin código de ningún lenguaje: sus
    cuerpos corren sobre la proyección en memoria (`CatalogTest`).
  - `ChangeSignature`: el único compuesto escrito en Java, porque su parámetro es una lista de registros.
- **Qué ignora.** Lenguajes y sesión. De la verificación sabe solo que existe: la pide a la transacción en la que
  corre.
- **Prueba de autonomía.** `RunTest`, sobre `MemoryProjection`: encapsulate-field corre sin motor; dentro de una
  transacción su expansión da la secuencia esperada; y `try` y `oblige` se comportan como se espera en cada lugar.
- **Estado actual** (fase 5 cerrada, 2026-10-31). `ide.refactoring` (`Refactoring`, `Library`, `Run`, `Args`,
  `Value`, `Primitives`, `ChangeSignature`) e `ide.refactoring.script`. Los primitivos se leen del vocabulario:
  nada se registra a mano. Desde la etapa 2 no quedan fugas de Java en el catálogo. `import` dejó de ser una en el
  ítem 1: es un retarget (funciones `unresolved-in` y `types-named`). En el ítem 4 los cuerpos de generate-getter,
  generate-setter e introduce-factory pasaron al lenguaje de cuerpos (decisión 12), y generate-setter perdió su rama
  para estáticos: cómo se califica un campo lo decide el lenguaje. `default-value` le pregunta a las reglas. En el
  ítem 5 llegó pull-up-unifying (sube un miembro y fusiona en él el mismo miembro de los hermanos: mismo nombre,
  firma, tipo, cuerpo y estaticidad), merge-duplicate pasó a ser un solo `delete ... into=`, `member-named` compara
  también los tipos de los parámetros de un método, y `can-access` le preguntaba a las reglas del driver si un sitio alcanza algo (se fue en la fase 0 de la etapa
  3: quién puede usar qué lo dice `java.rules`).
  Con los usos de miembros sin resolver, `unresolved-in` toma qué uso se busca (`type`, `value` o `call`), `site`
  y `scope` leen un uso sin resolver, `similar` da los miembros de un tipo cuyo nombre está a una o dos letras, y
  se fue `is-type-name`. Para create-method, `expected` y `arguments` (pares nombre y tipo) le preguntan a la
  proyección lo que el uso necesita (por eso las funciones tienen la proyección, no solo el programa y las reglas), y `last` acompaña a `first`. El lenguaje de cuerpos ganó `return default`: lo que el tipo del método
  vale al empezar, o nada si no responde nada.
  Llegaron generate-constructor (con `create CONSTRUCTOR`, que ahora entienden los dos drivers; el cuerpo se arma en un
  bucle con `said(e)`, cómo nombra un cuerpo a un elemento, y `;` separa sentencias del lenguaje de cuerpos),
  make-final y make-static: una línea cada uno, y el compilador decide si quedan. Un parámetro puede declarar un
  valor sugerido (`default "Abstract${name(type)}"`) y un tipo `lines` (las líneas de la selección), y un refactoring
  responde sus sugerencias (`suggestions`) y de dónde salen sus elementos (`from "fields(type)"`, `options`). Extract
  acepta un fragmento que termina el método con su último return. El lenguaje de cuerpos ganó la llamada
  (`#{f}.#{m}(...)`, `#{m}(...)`; en un método que no responde nada, un return de una llamada es la llamada), y con
  ella generate-delegates (los Delegate Methods de IntelliJ) y rename-with-delegate (el viejo nombre llama al nuevo).
  introduce-constant (el Introduce Constant de IntelliJ) es una composición de primitivos: extract de la expresión a
  un método, un campo `static final` que empieza con `#{m}()` (un inicializador dicho sin lenguaje, cuyos usos son
  del campo), el retarget de la llamada al campo (una llamada que no pasa nada se vuelve una lectura), el inline de la
  llamada en el inicializador y el delete del método. Una expresión que usa un local o el objeto no es constante: el
  compilador lo dice y la transacción se deshace.
  Desde la segunda parte de la fase 4 de la etapa 3 (decisión 34), un refactoring puede ser una intención (`intent`):
  sus parámetros y un `want`, lo que tiene que valer al final, escrito en el lenguaje del planificador. El script lo
  guarda como texto, y correrlo es pedírselo al planificador de la biblioteca (`Planner`, `Run.want`). Sin
  planificador, se rechaza. pull-up-field es la primera.
- **Abierto.** El rastro muestra los cuerpos con ids, no con direcciones. De la lista de la
  spec faltan annotate, use-supertype-where-possible,
  extract-interface-and-use, fold-constructors y delete-tree; y lo que trabaja sobre locales (extract variable,
  inline variable, introduce parameter desde una expresión), que el grafo no tiene. Introduce field se haría como
  introduce-constant, con el campo de instancia; así se hizo introduce-field.

### 5.7 `knowledge`: planificador por metas

- **Qué es.** Dada una base de metas (qué debe valer para que un problema desaparezca) y efectos (qué hace valer un
  predicado, bajo qué condición, con qué costo, con qué preguntas), propone planes ordenados por costo con una traza
  explicable. Podría planificar otra cosa que código.
- **Dominio.** `Goal(code, predicate)`, `Fact(predicate, when)` (cuándo vale un predicado), `Effect(name, title,
  makes, when, for, needs, cost, asks, do)`, `Predicate`, `Search` con su traza, `Plan`. Implementa `Remedy` y `QuickFix` del motor y ejecuta acciones por el `Run` de
  `refactoring`. ArchUnit garantiza que nadie más implementa `Remedy` ni `QuickFix`.
- **Qué ignora.** Lenguajes, texto, sesión.
- **Prueba de autonomía.** Una base de tres efectos sobre predicados inventados, sobre `MemoryProjection`.
- **Estado actual** (fase 5 cerrada, 2026-10-31). `ide.knowledge`, autónomo. Evalúa condiciones y títulos con el
  lenguaje de scripts (`Script.evaluate`, `Script.render`) y ejecuta los efectos con el `Run` real, directo sobre la
  proyección que le dan. Ya no tiene un contexto propio donde `attempt` no intentaba y `oblige` no obligaba. Los
  hechos entran tipados (site y target son elementos) y sus preguntas muestran los elementos por dirección. Tests
  propios (`KnowledgeTest`) con metas y efectos escritos en el test. Desde la fase 6 trae su base estándar
  (`Knowledge.standard`) y sabe qué hechos da una violación o un diagnóstico (`search(Violation)`,
  `search(Diagnostic)`); antes la sesión cargaba la base por su ruta y armaba esos hechos. Desde la etapa 2 el fix de
  import liga el uso sin resolver al que apunta el diagnóstico con un tipo de ese nombre: un retarget, sin Java. Desde
  el ítem 4 el valor que propone el fix de un `final` sin inicializar lo dan las reglas del lenguaje, y el que se
  escribe es el del usuario, en su lenguaje: la base no escribe código. Desde el ítem 5 encadena hacia atrás: lo que
  un efecto necesita (`needs`) vale por un `fact` o lo hace antes otro efecto, deducido igual, con los topes de la
  spec (planes de hasta 4 efectos, 8 niveles de pregunta y respuesta, los 12 más baratos). El `why` anida cada
  necesidad bajo la respuesta que la tiene. El fix de import necesita que el archivo alcance el tipo: uno
  package-private de otro paquete se abre antes de importarlo; antes se importaba solo y quedaba un error. Desde los
  usos de miembros sin resolver, cada fix parte del uso y no del nombre: import, de un tipo; create-field, de un
  valor, y crea el campo en el tipo donde se busca (`create field size in V` para `v.size`); y use-similar y
  call-similar cambian un nombre mal escrito por un miembro accesible de ese tipo cuyo nombre está a una o dos letras
  (`change to count`, `change to size(..)`). create-method crea el método que una llamada no encuentra, donde lo
  busca, con un parámetro por argumento (nombrado y tipado por el driver), estático si se llama desde un lugar
  estático de su propio tipo, y con `return default`; pregunta el tipo de retorno y propone el que espera el lugar de
  la llamada. create-field propone también ese tipo, ya no `int`. Un `ask` acepta comillas dentro de `${...}`.
  Con el compilador de Eclipse llegaron los quick fixes de los IDEs para sus problemas, cada uno deducido: implementar
  los métodos que los supertipos dejan abstractos (`unimplemented`, sin lenguaje) o hacer la clase abstracta; hacer
  estático un método usado desde un lugar estático; quitar un miembro privado sin uso (un warning); y abrir lo que
  un archivo no ve. Para eso último, los argumentos de una meta son expresiones sobre los hechos
  (`accessible(top-in(path), element-at(path, line, column))`), y un diagnóstico da también su columna. Un diagnóstico
  trae lo que el compilador nombra en él (`arguments`, escritos como el archivo los escribe), y el contrato responde
  en qué elemento cae una posición (`inside`, en la obligación 9): con eso, cambiar el tipo de retorno de un método o
  el tipo de un campo al del valor que recibe.
  Un `final` sin inicializar se inicializa también en los constructores, deducido de lo que el lenguaje dice: un campo
  final vale por su declaración o por cada constructor de su tipo, y un tipo que no declara constructores tiene el
  que da el lenguaje, que no asigna nada. Son dos reglas: `initialize-in-constructors` necesita
  `constructed-explicitly(tipo)`, un `fact` que vale si el tipo declara constructores, y si no vale lo hace antes
  `declare-constructor`. El planificador encadena solo: sin constructor ofrece `create constructor Q(), then
  initialize limit in the constructors`; con constructores, `initialize limit in the constructors` a secas. Cada
  constructor toma el valor (`create PARAM` con el argumento que pasan los `new`, el valor inicial del tipo según las
  reglas) y lo asigna después de lo que ya hacía: primero con `...` en el lenguaje de cuerpos, y desde la fase 3 de
  la etapa 3 creando una sentencia al final del cuerpo (sus usos se suman, no reemplazan a los viejos). Eclipse dice el problema en el constructor que no
  asigna, nombrando el campo en sus argumentos: la meta es `initialized(first(arguments))`.
  Un constructor no puede asignar un `final` de su superclase (`FinalFieldAssignment`): `pass-to-superclass` hace que
  el constructor de la superclase tome el valor y lo asigne (declarándolo antes si no hay, por la misma
  `constructed-explicitly`), y el constructor se lo pasa primero (`#{constructor}(valor)`, que Java escribe
  `super(...)`) en vez de asignarlo. Desde la fase 3 de la etapa 3 lo hace con sentencias: busca la que asigna el
  campo (`giving`), crea la llamada con lo que da (`value`), que va primera, y borra la asignación; si esa sentencia
  no se puede decir sin lenguaje, no se ofrece. Conserva el sentido, así que también repara: un extract
  superclass que sube un `final` que el constructor inicializa deja `super(limit)`, como IntelliJ.
- **Desde la fase 0 de la etapa 3.** Además de efectos, el conocimiento tiene definiciones (`def`, Datalog que evalúa
  `rules`, en `java.rules`): un predicado definido se hace verdadero por abducción. Se lee la definición de izquierda
  a derecha; lo que el grafo tiene (`GraphFacts`, una vista de `Program` sin lenguaje) liga variables, y un hecho del
  grafo con sus argumentos ligados que no vale se cambia con el primitivo que lo cambia (`Abduction`: visibilidad,
  padre, lugar, a qué liga un uso, un constructor que nadie tiene). Lo que una definición necesita de otra se hace
  antes, igual que las necesidades de un efecto. `say` dice cada cambio como lo dice el lenguaje, y `order` da un
  orden de valores. La primera definición en pasar fue quién puede usar qué (el efecto `open-visibility` se fue): ya
  no se prueba `protected` donde no alcanza. Después, dónde se usa un miembro sin decir de quién es (JLS 8.2) y el
  orden de los inicializadores (JLS 8.3.3): `pull-up-dependency` se fue, y subir un campo que un inicializador usa
  es un cambio de padre y uno de lugar que se juntan en un solo `Move`. Y que un override es por lo menos tan visible
  como lo que sobrescribe (JLS 8.4.8.3): `widen-override` se fue.
  Con los estáticos llegaron las restricciones: `rule nombre … for … where … must … end` dice lo que Java exige, y se
  compila a la relación de sus violaciones (`violates-nombre`); `problem código is nombre(args)` dice que un error es
  esa regla rota ahí. Una regla rota se hace cumplir quitándole uno de los hechos en que se apoya (la abducción
  también sabe quitar: `static` se pone o se saca), y después de cada cambio supuesto se buscan las violaciones nuevas
  alrededor de lo que cambió y se arreglan en el mismo plan, nunca deshaciendo un cambio propio. `promise relación of
  tipo "por qué"` corta un camino, y el rastro dice por qué. Las reglas de estáticos (JLS 8.4.3.2 y 15.12.3: un
  miembro estático usa lo de instancia de su objeto solo a través de un objeto, y lo que se usa a través de un tipo es
  estático), con el `static` de un campo como promesa, reemplazaron a `make-static`. De un método estático que llama a
  uno de instancia salen, deducidos: hacerlo no estático, o hacer estático lo llamado y lo que eso llame; el camino
  que termina en un campo se corta en la promesa, y el que deja sin estático a algo llamado a través del tipo se
  descarta solo.
  Una promesa se rompe solo a propósito: como el primer cambio de un plan, el cambio del que el fix trata; ese plan
  cambia el sentido (se ofrece, ninguna reparación lo toma) y cuesta más. Como consecuencia dentro de una cadena,
  nunca: ahí corta el camino. Así "make count not final" sale de quién puede darle valor a un `final` (JLS 8.3.1.2)
  con `final` de un campo como promesa, y `unfinal` se fue. `unimplemented` pasó a ser una consulta de `Program`.
  Después, que una clase no abstracta implementa lo que hereda sin cuerpo (JLS 8.1.1.1) y que nadie crea objetos de
  una abstracta (15.9.1), con el `abstract` de una clase como promesa: `make-abstract` se fue, y ya no se ofrece
  volver abstracta una clase de la que algo crea objetos. Para verlo hubo que preguntar las reglas enteras después de
  cada cambio, no solo alrededor de lo cambiado: una violación puede llegar a lo cambiado por su cuerpo. Y que lo que
  un método devuelve, o con lo que arranca un campo, es del tipo que declara (JLS 14.17, 8.3.2), con el tipo de un
  miembro como promesa y el del valor como lo dice el compilador (`argument(0, t)`): se fueron `change-return-type`
  y `change-field-type`. Por último, que un `final` sin valor donde se declara lo recibe de cada constructor que no
  delega con `this(...)` o de un inicializador (JLS 8.3.1.2, 16.9): `drop-final` se fue. Hizo falta un hecho nuevo
  del driver, `INITIALIZED` (el campo recibe su valor donde se declara; la obligación 8 lo pide). Y enseñó que una
  regla tiene que nombrar solo lo que la viola: la de un `final` es del campo, no del constructor que le falta, así
  declarar el constructor que el lenguaje ya daba no cuenta como una violación nueva.
  Crear con forma: hay relaciones que se crean cuando no hay nada (`member(e, dueño, nombre, tipo)`, `parameter(p,
  callable, nombre, lo que pasan)`, `constructor-for(k, tipo, uso)`), y lo creado trae al estado supuesto lo que es
  (tipo, padre, nombre); `takes-arguments(m, uso)` da un parámetro por argumento. Lo que una definición pide de lo que
  se está creando se deduce en la misma suposición; lo que pide de lo que existe sigue siendo una pregunta aparte.
  Lo creado y lo que se le fija son un paso, con un título; un marcador lo nombra hasta que existe. `ask nombre
  "pregunta" for relación of new tipo` declara lo que solo la persona sabe: lo deducido queda como respuesta
  propuesta. Así un nombre usado como valor es un campo del tipo donde se busca, o un parámetro del método donde se
  usa (JLS 6.5.6.1): se fueron `create-field` y `create-parameter`. Después, que un nombre llamado es un método del
  tipo donde se busca, que una creación sin constructor que la haga pide uno que tome lo que pasa, y que un tipo
  escrito que no existe lo declara el paquete del archivo (JLS 15.12, 15.9.3, 6.5.5): se fueron `create-method`,
  `create-constructor`, `create-class` y `create-interface`. Para el método estático hizo falta decir qué es
  condición en una definición: `given static(s)` se comprueba y nunca se cambia; sin eso, la abducción también
  proponía volver estático a quien llama. Por último, sacarle a `complete` el método heredado sin cuerpo es
  implementarlo (`implement-methods` se fue: todos los de una clase son un paso), y darle a un `final` su valor donde
  se declara es una pregunta sobre lo que existe, `ask value "initial value" for initialized of field default
  start-of`, que propone lo que el tipo empieza valiendo (`initialize-in-declaration` se fue). Y que un tipo escrito en un archivo nombra un tipo
  de ese nombre que el archivo puede usar (JLS 6.5.5): el efecto `import` se fue, y ligar el uso es importar; si el
  archivo no puede usarlo, abrirlo antes sale de la misma definición de acceso. Una definición que no aplica queda
  en el rastro con el literal donde se trabó (`X resolves: unresolved(u, a/P.java, total, type)`).
  Desde la segunda parte de la fase 4 de la etapa 3 (decisión 34) es también el planificador de las intenciones del
  catálogo (`Planner`; `Knowledge.standard` se registra en la biblioteca). Un `want` se lee como el cuerpo de una
  definición y se hace valer igual, y lo que rompe se repara en el mismo plan sin que lo pedido cuente para el tope.
  Se toma el plan más barato que conserva el sentido; si las reglas no reparan nada, se hace el `want` solo y el
  reparador sigue después.
  Una excepción chequeada que no se atrapa ni se declara (`ecj.UnhandledException`) la declara el método donde se
  tira (`declares-thrown`, con `throws(m, e)` como relación que se prende): no cambia lo que corre, así que también
  repara. extract-method deja así los `throws` que necesita el método nuevo, una excepción por ronda.
- **Abierto.** Las reglas son recetas escritas a mano: se deduce cuándo aplicarlas y cómo encadenarlas, no la regla
  misma. La propuesta de la etapa 3 (decisión 32) las reemplaza por reglas generales del lenguaje, de las que se
  deducen las transformaciones, y deja los refactorings como intenciones; necesita las sentencias en el grafo. Un predicado que ningún `fact` define siempre se hace, aunque
  ya valga.

### 5.8 `session`: el espacio de trabajo

- **Qué es.** El ensamblado, el único que no sería una librería ajena. Abre un proyecto, ejecuta las operaciones
  del usuario como transacciones, mantiene undo, log y estado guardado, y expone todo como capacidades.
- **Dominio.**
  - `Session`: las operaciones del usuario, cada una una transacción (invoke, preview, expand, edit, fix, define),
    con undo, el log de eventos y lo último guardado (diff, save). Un fix que no hace desaparecer su diagnóstico no
    se conserva.
  - `Fixes`: los quick fixes de una línea, sus preguntas y cómo se dedujeron. Un `Fix` es un diagnóstico y el plan
    que debería hacerlo desaparecer.
  - `Navigation` (callers, hierarchy, references, model) y `Completion`: preguntas sobre el programa. No conocen
    ningún lenguaje.
  - `Capabilities`: cada refactoring de la biblioteca y cada operación del workspace, con su esquema.
  - `Workspace`: la fachada para tests y adaptadores. Arma las partes y solo delega.
- **Regla propia.** No contiene lógica de otro dominio; su prueba es la inversa de las demás: que cada línea se lea
  con los términos de los otros productos.
- **Prueba de autonomía.** Cada colaborador se testea solo; la fachada solo delega.
- **Estado actual** (fase 6 cerrada, 2026-10-31). `ide.session`: `Session`, `Fixes` y `Fix`, `Navigation`,
  `Completion`, `Capabilities` y `Workspace`, cuyos métodos son delegaciones de una línea. `NavigationTest` y
  `CompletionTest` corren sobre la proyección en memoria; `FixesTest` y `SessionTest`, sobre un proyecto Java chico.
  Los tests anteriores de la sesión y los de aceptación pasan contra la fachada sin cambios.
- **Desde el ítem 6.** `Workspace.open(root)` lee las fuentes de una carpeta, registra los eventos en
  `.ide/events.jsonl` ahí y guarda ahí (`save()`); uno hecho en memoria no tiene dónde guardar. Entraron las
  capacidades que pidieron los adaptadores: `save`, `edit`, `asks` (las preguntas de un fix, con su default), `at`
  (el elemento y su clase en una posición), `where` (dónde está declarado), `elements` (de unas clases, o de un
  archivo) y las respuestas de `fix`. `ls` responde ordenado y las respuestas con campos los mantienen en orden. Una
  carpeta se lee sin lo que escriben los builds y las herramientas: carpetas `target`, `build`, `out` y ocultas.
  Abrir una carpeta le pregunta a `maven` el classpath del proyecto (recordado en `.ide/classpath.txt`) y se lo da al
  driver; la capacidad `classpath` dice cuáles son las bibliotecas o qué salió mal, y abrir queda en el log.
- **Abierto.** `Capabilities` lista las operaciones a mano: si un día se desfasan de la fachada, se derivan de ella.
  `model` sigue sin ser capacidad: ningún adaptador la pidió (`elements` alcanzó).

### 5.9 `maven`: lo que Maven dice de un proyecto

- **Qué es.** El classpath de un proyecto Maven: los jars contra los que compila su código y corren sus tests, en
  todos los módulos si es un reactor. Se lo pregunta a Maven mismo (`dependency:build-classpath`, scope test), que es
  la autoridad sobre padres, BOMs importados, exclusiones y mediación de versiones: reimplementar eso sería adivinar.
- **Para qué lo usarías.** Cualquier herramienta que analice el código de un proyecto Maven sin correr su build: un
  linter, un indexador, otro IDE.
- **Dominio.** `Maven.project(carpeta)`: el proyecto es la carpeta más cercana, en ella o arriba, con un `pom.xml`.
  `classpath(cache)`: lo que dijo Maven, recordado en un archivo con el tamaño y la fecha de cada POM del que depende
  (los del proyecto y los de arriba), porque preguntar tarda segundos; se pregunta de nuevo cuando un POM cambia.
  `Classpath(entries, problem)`: lo que Maven no pudo resolver, o Maven ausente, se dice, no se lanza.
- **Qué ignora.** Java, el IDE, la sesión. No declara ningún otro producto.
- **Prueba de autonomía.** `MavenTest`, con un POM en una carpeta temporal y una dependencia que está en `~/.m2`: el
  jar está en el classpath, la segunda vez sale del cache, y un POM cambiado lo pregunta de nuevo.
- **Estado actual** (2026-11-01). En `oozx`, un reactor de 25 POMs, el classpath de todos sus módulos son 118 jars, y
  preguntarlo tarda unos 25 s; el de un módulo, unos 10 s; recordado, nada.
- **Abierto.** Gradle. Las carpetas de clases (un módulo hermano compilado y no instalado) llegan a javac pero no al
  symbol solver, que lee solo jars.

### 5.10 `adapters`: cli, ui, mcp

- **Qué es.** Traductores: argumentos a capacidades, respuestas JSON a texto o pantalla. CLI primero, después UI
  Swing con keymap de Eclipse, después MCP.
- **Regla propia.** Sin lógica y sin dependencias más allá de `session` y `core`. Si un adaptador necesita un `if`
  sobre el dominio, falta una capacidad.
- **Estado actual** (ítem 6 de la etapa 2, 2026-11-01). Tres módulos, cada uno con su `AdapterTest`, y los tres
  hablan con el IDE solo por capacidades, como JSON.
  - `cli`: `ide <carpeta> <capacidad> [args]` corre un comando y guarda lo que cambió; `--script` corre un archivo de
    comandos hasta que uno falla; sin comando es un shell hasta `exit`. Las palabras se ligan a los parámetros por
    posición o por nombre, según el esquema (una lista de elementos con comas, una verdad como true o false,
    `param.clave=valor` para los que toman varios por nombre); la respuesta JSON se escribe como texto. `help` es el
    esquema de cada capacidad. `Command`, `Text`, `Cli`, `Main`; `target/ide-cli.jar`.
  - `ui`: una ventana Swing que se ve y se usa como IntelliJ: Darcula (FlatLaf) con Inter y JetBrains Mono, el editor
    RSyntaxTextArea con los colores Darcula, ocurrencias del nombre bajo el cursor, problemas subrayados y marcados en
    el margen, Ctrl+Click para navegar, y completion en un popup en el cursor (Ctrl+Space o después de un punto) con el
    ícono de cada candidato. Quick fix, menú de refactor, rename en el lugar e ir a línea son popups chicos sin marco
    en el cursor; open type y outline, popups de búsqueda que filtran por iniciales; el formulario de un refactoring es
    un diálogo con vista previa coloreada y botón Refactor. Abajo, tool windows: Problems, Changes (diff coloreado),
    Steps (árbol), Output y Results, con íconos. Desde la noche del 2026-11-01: los menús son los de IntelliJ (File,
    Edit, View, Navigate, Code, Refactor, Build, Help; cada refactoring de la biblioteca en su lugar, el resto en More
    Refactorings) y cada acción tiene la tecla de IntelliJ y la de Eclipse; Alt+Enter (o Ctrl+1) ofrece los quick fixes
    de la línea y después los refactorings que toman lo que está bajo el cursor o aquello en lo que está; una lamparita
    roja o amarilla en el margen lo anuncia, en el lugar del ícono del problema; clic derecho abre un menú contextual
    con submenú Refactor; Ctrl+W y Ctrl+Shift+W agrandan y achican la selección por la sintaxis (`around`); Alt+Insert
    genera; Ctrl+Shift+A busca una acción. Los formularios toman el elemento bajo el cursor o el que lo contiene
    (`inside`), las líneas seleccionadas, los nombres sugeridos (`suggest`) y solo los elementos que el refactoring
    acepta (`options`), con casillas. Los problemas se subrayan en su columna y largo, rojos o amarillos, y se
    compilan mientras se escribe (`check`, como el reconciler de Eclipse); el árbol marca con un ícono chico el
    archivo y el paquete que tienen errores o warnings. Se prueba sin pantalla: los diálogos son
    una interfaz (`Dialogs`) que los tests guionan. Sin carpeta abre un proyecto de ejemplo (una tienda, copiada a
    `~/.ideseed/example`) cuyos archivos dicen qué probar en cada uno; `ExampleTest` verifica que cada refactoring y
    quick fix propuesto funciona. Una terminal como la de IntelliJ es una vista más (Alt+F12): JediTerm la dibuja y
    pty4j le da una terminal real, para que un programa que la necesita, como Claude Code, funcione adentro. Corre el
    shell del usuario en la carpeta del proyecto y arranca la primera vez que se muestra; mientras tiene el foco, las
    teclas son suyas. Es solo una vista: no sabe nada del IDE. JediTerm se publica en el repositorio de JetBrains, no
    en Maven Central. `target/ide-ui.jar`.
  - `mcp`: JSON-RPC 2.0 por stdio, una línea por mensaje: `initialize`, `ping`, `tools/list` (cada capacidad una
    herramienta, su esquema un JSON Schema) y `tools/call` (lo que responde la capacidad, o un resultado de error).
    Si una llamada cambia las herramientas, como `define`, avisa con `notifications/tools/list_changed`.
    `target/ide-mcp.jar`. El protocolo (`Server.handle`) está separado de sus transportes: stdio (`run`) y Streamable
    HTTP (`Http`), solo en 127.0.0.1 y con un token: `POST /mcp`, que responde JSON, o un flujo de eventos cuando hay más
    de un mensaje, y 202 a una notificación. Cada mensaje corre en el turno que le dan.
  - **Decisión: la ui depende de mcp.** Para que un agente (Claude Code en la terminal) trabaje sobre *el mismo* IDE que
    muestra la ventana, y no sobre un `Workspace` propio que la ventana no ve, la ventana sirve su IDE por HTTP. Cada
    llamada corre en el hilo de Swing y después la ventana se muestra de nuevo. Al abrirse escribe dónde encontrarla, con
    el token, en `~/.ideseed/mcp/ideseed-<puerto>.json` (solo legible por el usuario; se borra al cerrar), le pasa la
    ruta a la terminal en `IDESEED_MCP_CONFIG`, y Ctrl+Esc (la tecla del plugin de Claude Code en JetBrains) corre ahí
    `claude --mcp-config "$IDESEED_MCP_CONFIG"`. Es la única dependencia entre adaptadores: mcp sigue sin conocer a
    nadie, y la ui solo lo enciende.
  - **Decisión: el disco y el IDE no se separan mientras trabaja un agente.** Un agente tiene sus propias herramientas
    de archivos, además de las del IDE. Por eso, antes de cada mensaje MCP la ventana toma lo que cambió en disco
    (`sync` de session: cada archivo es una edición a mano, que reconcilia la identidad como cualquier otra), y
    después de cada llamada guarda. Un `Watcher` hace lo mismo cuando la carpeta cambia por cualquier otro motivo.
    Si un archivo cambió en disco y también en el IDE, es un conflicto: se dice y no se toca. Un archivo borrado en
    disco se informa pero no se sigue. Guardar después de una llamada de un agente guarda también lo que la persona
    tenía sin guardar.
  - **Decisión: los planes que se siguen son dominio de session, no del agente.** `Plans` (para no confundirlo con
    el `Plan` de reparación del engine) guarda varios planes; uno solo está activo y los demás quedan en pausa,
    terminados o abandonados. Cada plan es un árbol de tareas: las de arriba son líneas de acción y las de abajo, sus
    subtareas. Cada tarea tiene un id estable (`t7`), así que replanificar (agregar, mover, descartar) no cambia quién
    es quién. Además tiene descripción, estado (pendiente, en curso, bloqueada, en pausa, hecha o descartada), las
    tareas que espera (`after`, sin ciclos), enlaces al código y una línea de tiempo de quién dijo o hizo qué. Todo se
    guarda en `.ide/plans.json` y cada cambio es un evento del log. Reemplaza al `WorkPlan` de una sola lista, cuyas
    tareas se identificaban por su posición.
  - **Decisión: lo que dice la persona cambia el plan al instante y le llega al agente una sola vez.** Con `steer`
    puede pausar, reanudar, comentar, pedir que se replanifique o cambiar de plan. El agente no escucha mientras
    trabaja, así que el mensaje ("The person ...") queda esperando. La ventana lo escribe en la terminal de Claude si
    está ahí (y lo marca como dicho); si no, viaja con la próxima respuesta del servidor MCP. `steering` devuelve lo
    que todavía no se le dijo.
  - **Decisión: el avance de una tarea lo ve el IDE, no lo cuenta Claude.** Contar cada paso le costaría tokens a
    Claude. Por eso la ventana lo lanza con un hook de Claude Code (`--settings`, un `PostToolUse` que hace curl a
    `activity` con el mismo token). Después de cada herramienta, el hook le avisa al IDE qué hizo, y eso queda como
    un paso de la tarea que empezó último y sigue en curso. El hook manda el evento por un pipe y no como argumento,
    porque un Read grande superaría el largo máximo de un argumento. Las respuestas a `activity` no llevan las
    indicaciones de la persona, porque nadie las lee. Stop es inmediato: la ventana aprieta Esc en la terminal de
    Claude y después escribe el mensaje, separados para que no se lean como Alt+tecla.
  - **Decisión: solo se escribe en la terminal si Claude está ahí y no está preguntando nada.** Que Claude corre se
    sabe por los procesos de la terminal, no por haberlo lanzado: si se cerró, el mensaje irían al shell como un
    comando. Si Claude está en un diálogo ("Enter to confirm", un permiso), el Enter del mensaje lo contestaría, así
    que el mensaje queda esperando y viaja con la próxima respuesta del IDE. La excepción es Stop: su Esc contesta que
    no. El Enter va aparte del texto, porque junto con él Claude Code lo toma como un pegado. La terminal saca las
    variables `CLAUDECODE*` y `CLAUDE_CODE_*` heredadas: sin eso, un Claude lanzado desde un IDE que a su vez se abrió
    desde Claude se cree una subsesión y no guarda la conversación. Los settings con que se lanza Claude preaprueban
    las herramientas del IDE (`mcp__ideseed`), que se verifican y se pueden deshacer.
  - **Decisión: cerrar el IDE no corta la conversación con Claude.** Los eventos del hook traen el `session_id` de
    Claude, y session lo guarda por proyecto (`Conversation`, en `.ide/conversation.json`). Al cerrar, la ventana
    anota si Claude seguía corriendo en su terminal; al abrir el proyecto otra vez, si seguía, lo lanza con
    `--resume` y la configuración MCP nueva (cambian el puerto y el token, pero el servidor se sigue llamando
    `ideseed`). Si session no tiene un id guardado, se usa la conversación más reciente que Claude Code guarda de esa
    carpeta (`~/.claude/projects/<carpeta>`), que es conocimiento de Claude Code y por eso vive en la ventana. Antes,
    cerrar la terminal dejaba vivos el shell (un bash interactivo ignora SIGTERM) y el Claude de adentro; ahora se
    terminan los hijos primero y después el shell, a la fuerza si hace falta.
  - **Decisión: la atención va en los dos sentidos y también es dominio de session.** La ventana no registra
    capacidades propias (eso haría que un adaptador tuviera lógica y dependiera de otro para algo más que servir). En
    cambio, `Attention` guarda dos cosas. La primera es el último lugar al que un agente señaló, con `point`: un
    elemento (se busca dónde está declarado) o unas líneas de un archivo, con una nota. Va numerado, para que quien lo
    muestra sepa que es nuevo, y es un evento del log. La segunda es dónde mira la persona: la ventana lo dice con
    `look` antes de cada llamada de un agente, y no es un evento porque cambia con cada paso del cursor. `looking` lo
    devuelve junto con el elemento bajo el cursor y aquello en lo que está. Así Claude entiende "esto" y la ventana
    solo muestra: abre seleccionadas las líneas (si es un elemento, la declaración entera) y dice la nota en Output.
  - **Decisión: cómo trabajar sobre el IDE lo dice el IDE.** La capacidad `guide` devuelve un texto (`guide.md` en
    session) que explica el plan, la atención y los refactorings en lugar de editar texto. El servidor MCP lo pasa
    como `instructions` al inicializar, así que Claude lo recibe sin que nadie lo ponga en un `CLAUDE.md` del proyecto.
    Un test verifica que cada herramienta que nombra exista.
- **Abierto.** En la UI: el diff lado a lado, el rename en el lugar con recuadro, el árbol de tipos de F4 y Ctrl+T
  como popup, y sugerencias que se recalculan al cambiar un campo del formulario. El runner de `main`.

### 5.11 `rules`: Datalog

- **Qué es.** Un Datalog: relaciones que se definen con reglas sobre hechos, con negación estratificada. No sabe de
  programas, de lenguajes ni de refactorings, y se usa como una herramienta externa: `knowledge` le pasa reglas y
  hechos, y le pregunta.
- **Para qué lo usarías.** Cualquier consulta con reglas sobre hechos que viven en otro lado: dependencias, permisos,
  un grafo de lo que sea.
- **Dominio.**
  - `Term` (`Variable`, `Constant`), `Atom`, `Literal` (`Positive`, `Negative`, `Comparison` con `=` y `!=`),
    `Clause`.
  - `Rules`: el programa. Se arma desde cláusulas o desde texto escrito como lo lee cualquier Datalog
    (`ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).`), y se rechaza si podría significar dos cosas: una variable
    que ningún átomo de su cuerpo liga, o una relación que depende de su propia negación. Dice qué relaciones define
    y cuáles son recursivas.
  - `Tuple` y `Facts`: los hechos se piden por patrón (un valor donde se sabe, nada donde vale cualquiera); son una
    vista sobre lo que los tenga, no una copia. `with(agregados, quitados)` y `and(otros)`.
  - `Engine` y `Evaluation`: `answers(consulta)`, `holds(átomo)` y `suppose(agregados, quitados)`, un estado
    supuesto tan barato de armar como de tirar. El motor estándar (`OnDemand`) evalúa desde la consulta hacia abajo,
    recuerda lo que calculó por patrón, calcula entera hasta su punto fijo una relación recursiva, y pide los hechos
    solo con los valores que ya tiene.
- **Qué ignora.** El grafo del programa, el lenguaje de superficie de las reglas (`def`, que vive en `knowledge`), el
  planificador y los primitivos.
- **Prueba de autonomía.** `RulesContract`: lo que cualquier motor contesta igual (consultas por patrón, uniones,
  recursión hasta el punto fijo, negación, comparaciones, el orden de las respuestas, un estado supuesto que no toca
  el original, hechos pedidos solo para las relaciones que las reglas usan). Corre contra el motor estándar.
- **Abierto.** Un segundo motor (uno maduro) entra como otra implementación de `Engine`, con un adaptador que
  traduzca las cláusulas a su dialecto y los mismos tests de contrato; ese día `RulesContract` se muda a un
  `rules-kit`. No hay agregados ni símbolos de función: las reglas los evitan (un `count` se dice como "un parámetro
  sin argumento").

## 6. Cómo se testea

Maven asegura que los productos no se conocen; los tests de producto aseguran que no se necesitan. Hay tres clases
de test, cada una con su lugar y su rol.

| Clase      | Dónde vive                                   | Qué prueba                                                | Contra qué                               |
|------------|----------------------------------------------|-----------------------------------------------------------|------------------------------------------|
| Producto   | el módulo del producto                       | el producto usado como herramienta, en su vocabulario      | dobles de lo que necesita de afuera      |
| Contrato   | junto al contrato (`projection-kit`, fase 2) | que cada implementación cumple lo que sus usuarios asumen  | cada implementación, la real y la falsa  |
| Aceptación | `acceptance`                                 | un escenario de usuario de punta a punta                   | todos los productos reales y texto Java  |

- **Producto.** Lo entiende alguien que no sabe que existe el IDE: es la documentación del producto y muestra cómo
  se usaría en otro proyecto. Si no se puede escribir sin nombrar otro producto, al dominio le falta un concepto o
  al pom le sobra una dependencia. Los casos borde viven acá, en el producto dueño de la regla: cuántas veces se
  evalúa un argumento en un inline es semántica de Java y va en `java`; los topes del reparador van en `engine`.
- **Contrato.** Hace confiables a los dobles. El motor se prueba sobre la proyección en memoria, y el contrato
  verifica que la proyección Java se comporta igual en todo lo que el motor asume. Sin contrato, un test de
  producto puede pasar sobre un doble que miente.
- **Aceptación.** Pocos por funcionalidad: el camino feliz y el aborto que importa. Prueban que la composición
  entrega el valor; no repiten los casos borde de cada producto.

La autonomía no se demuestra con funcionalidad de más. Un producto aprende lo que la composición necesita, pero lo
aprende en su propio idioma; lo que nadie pide no se construye (principio 8).

El ciclo de TDD es doble: se descubre de afuera hacia adentro y se implementa de abajo hacia arriba.

1. Un test de aceptación en rojo describe el valor que se quiere.
2. Se pregunta qué tiene que aprender cada producto para que pase. Cada respuesta es un test de producto en rojo,
   escrito en el vocabulario de ese producto.
3. Cada producto pasa a verde, de abajo hacia arriba, con refactor adentro del producto.
4. Si un producto cambió una interfaz, el contrato se amplía y corre contra todas las implementaciones.
5. La aceptación pasa a verde. Si pasó sin que ningún producto aprendiera nada, la funcionalidad era pura
   composición, como un script nuevo del catálogo, y alcanza con la aceptación.

Un bug que encuentra un test de aceptación se fija con un test en el producto que lo causa, y la aceptación queda
como red. Ejemplo: el rename de un tipo usado como parámetro apareció en `RenameTypeTest`, de aceptación; la causa
estaba en la identidad, y `IdentityTest` lo fija en el vocabulario de `program`, sin Java.

Al empezar la fase 2 la pirámide estaba invertida: casi todo se probaba de punta a punta y cinco productos no
tenían ningún test propio. Cada fase baja a los productos que toca los casos borde que les pertenecen. Los tests
nuevos de `engine` fijan comportamiento que ya existía: no lo condujeron, lo hicieron verificable sin Java.

| Módulo           | Al empezar la fase 2 | Al cerrar la fase 2 | Al cerrar la fase 3 | Al cerrar la fase 4 | Al cerrar la fase 5 | Al cerrar la fase 6 | Etapa 2, ítems 1 a 3 | Etapa 2, ítem 4 | Etapa 2, ítem 5 | Etapa 2, ítem 6 |
|------------------|----------------------|---------------------|---------------------|---------------------|---------------------|---------------------|----------------------|-----------------|-----------------|-----------------|
| program          | 8                    | 12                  | 12                  | 12                  | 12                  | 13                  | 19                   | 24              | 25              | 25              |
| projection-kit   | (no existía)         | 8                   | 8                   | 8                   | 8                   | 8                   | 10                   | 11              | 11              | 12              |
| java             | 0                    | 8                   | 24                  | 24                  | 24                  | 24                  | 36                   | 39              | 39              | 42              |
| engine           | 0                    | 9                   | 9                   | 17                  | 19                  | 19                  | 19                   | 20              | 21              | 21              |
| refactoring      | 1                    | 3                   | 3                   | 3                   | 11                  | 11                  | 11                   | 12              | 12              | 12              |
| knowledge        | 0                    | 0                   | 0                   | 0                   | 3                   | 5                   | 6                    | 6               | 8               | 8               |
| core, projection | 0                    | 0                   | 0                   | 0                   | 0                   | 0                   | 0                    | 0               | 0               | 3               |
| session          | 13                   | 13                  | 13                  | 13                  | 13                  | 22                  | 22                   | 22              | 22              | 25              |
| acceptance       | 102                  | 103                 | 104                 | 104                 | 104                 | 104                 | 107                  | 108             | 114             | 114             |
| cli              | (no existía)         |                     |                     |                     |                     |                     |                      |                 |                 | 12              |
| ui               | (no existía)         |                     |                     |                     |                     |                     |                      |                 |                 | 13              |
| mcp              | (no existía)         |                     |                     |                     |                     |                     |                      |                 |                 | 6               |

## 7. Decisiones

| #  | Tema                     | Decisión                                                                                   | Estado      |
|----|--------------------------|--------------------------------------------------------------------------------------------|-------------|
| 1  | Mapa y nombres           | core, program, projection, java, engine, refactoring, knowledge, session, adapters          | tomada, 2026-10-31 (10610bc) |
| 2  | Forma de los primitivos  | `Primitive` sellado y `apply(Primitive)` que devuelve `Change`; cada primitivo declara su intención | tomada, 2026-10-31 (1524f0c) |
| 3  | Vínculos                 | extends, implements y declared son referencias; `Link` reemplaza los `set` de tipos        | tomada, 2026-10-31 (4d16bfe, 5cceb1a) |
| 4  | Identidad                | `Address` tipada con ids de dueño y de tipos de parámetro; reconciliación estructural después | tomada, 2026-10-31 (67901c9; reconciliación en afd56af) |
| 5  | Edición                  | solo `EditSet` de texto; sin mutación del AST ni printer léxico                            | tomada, 2026-10-31 (3f994c2) |
| 6  | Hechos                   | conjunto cerrado y tipado (claves `Fact`; los valores siguen siendo texto)                 | tomada, 2026-10-31 (4d16bfe) |
| 7  | Cuerpos                  | fuera del grafo; el fragmento es una dirección opaca de la proyección (líneas en Java, posiciones de usos en memoria) | tomada, 2026-10-31 (f64f7dd) |
| 8  | Creación                 | `Create` recibe los hechos con que nace y, para un parámetro, el argumento de las llamadas  | tomada, 2026-10-31 (8f0ed6a) |
| 9  | Rastro                   | `Trace` como árbol de datos; un único render a líneas                                      | tomada, 2026-10-31 (34a3b0e) |
| 10 | Conformidad              | `projection-kit`: `MemoryProjection` y `ProjectionContract`, que corre contra cada driver   | tomada, 2026-10-31 (f64f7dd) |
| 11 | Referencias no resueltas | entran al programa como usos sin resolver, aparte de las referencias; import pasa a ser retarget | tomada, 2026-10-31 (0b40a6c, 0a738bf) |
| 12 | Cuerpos en plantillas    | lenguaje de cuerpos (`Body`, en `program`): return, asignación y new sobre elementos `#{id}`; cada proyección lo escribe en el suyo y la línea base espera los usos que nombra | tomada, 2026-10-31 (37c903e, 05de013) |
| 13 | Módulos Maven            | un módulo por producto, más `acceptance` para los escenarios transversales                  | tomada, 2026-10-31 (5d641a2) |
| 14 | Tests                    | producto, contrato y aceptación, en doble ciclo (sección 6)                                | tomada, 2026-10-31 |
| 15 | Ejecución                | un único `Run`, dentro de una transacción o directo sobre una proyección; reemplaza a `Ctx` | tomada, 2026-10-31 (6911992) |
| 16 | Valores de scripts       | `Value` tipado (`Elem`, `Ref`, `Text`, `Bool`, `Items`); id o dirección se resuelve en `Args` | tomada, 2026-10-31 (fb7e3e9) |
| 17 | Transacción y reparador  | la transacción nace con su reparador; `attempt(label, body)`, `commit()`, `Run.in(tx, library)` | tomada, 2026-10-31 (85bb676) |
| 18 | Tipos de biblioteca      | los que el workspace nombra son elementos `LIBRARY`, con su nombre como identidad; nada los cambia y la jerarquía es la del workspace | tomada, 2026-10-31 (a5fdf89) |
| 19 | Código en el catálogo    | el catálogo y el conocimiento no escriben código de ningún lenguaje: dicen cuerpos en el lenguaje de cuerpos, pasan lo que el usuario escribe y le preguntan a las reglas lo que solo el lenguaje sabe escribir | tomada, 2026-10-31 (37c903e, 77e0742) |
| 20 | Fusión                   | dos miembros pasan a ser uno con `Delete(element, into)`, sin estado intermedio: retarget y después delete no pueden fusionar un override en lo que sobrescribe | tomada, 2026-11-01 (8eb59b1) |
| 21 | Despacho                 | un override nuevo es un cambio de despacho solo si alguna llamada del método sobrescrito puede alcanzarlo; subir un método ya no aborta porque un hermano tenga la misma firma | tomada, 2026-11-01 (3909198) |
| 22 | Encadenamiento           | `fact` dice cuándo vale un predicado y `needs` qué requiere un efecto; los planes se deducen hacia atrás, de hasta 4 efectos, y una búsqueda responde los 12 más baratos | tomada, 2026-11-01 (8695c28) |
| 23 | Posiciones               | el contrato de la proyección dice dónde está declarado un elemento y qué elemento hay en una posición; el grafo sigue sin posiciones | tomada, 2026-11-01 (3223bf9) |
| 24 | Adaptadores              | un módulo cada uno, que declara `session` y `core` y usa solo eso (`AdapterTest`); hablan con el IDE solo por capacidades, en JSON; lo que un adaptador necesita y no es capacidad entra a `session` como capacidad | tomada, 2026-11-01 (cedbd23, 763d4e6, 8b760b3) |
| 25 | Classpath                | las bibliotecas de un proyecto se le preguntan a Maven, en un producto propio (`maven`), y se recuerdan mientras sus POMs no cambien; el driver las lee de los jars sin cargar clases, y javac compila siempre contra el classpath del proyecto, nunca contra el del IDE | tomada, 2026-11-01 (817a548, 854df27) |
| 26 | Usos de miembros sin resolver | un campo o método que no liga nada es un uso del programa solo si el resolvedor lo asegura (para una llamada: ningún método con ese nombre donde se busca); en la duda no es nada, como un local. El uso dice en qué tipo se busca, y los fixes parten de él | tomada, 2026-11-01 |
| 27 | Lo que necesita un uso   | qué tipo espera un uso sin resolver y qué pasa una llamada se le pregunta a la proyección por un uso (`needs`, obligación 10), no se guarda en el grafo: solo lo usa un fix, y así se prueba sin lenguaje | tomada, 2026-11-01 |
| 28 | Compilador               | el de Eclipse (ECJ) en vez de javac, incremental como su builder: compila lo que cambió y lo que nombra un tipo cuya forma cambió (class file con cambios estructurales, o que vino o se fue), leyendo lo demás de los class files anteriores. Da errores y warnings con línea, columna y largo; un warning no es una violación. Los códigos son `ecj.<problema>` | tomada, 2026-11-01 |
| 29 | Lo fijado                | un `Set` fija el atributo que da (visibilidad, static, abstract, final, tipo), no solo la visibilidad: una reparación que lo deshace se descarta. Antes make-final sobre un campo sin inicializar se "aplicaba" y el reparador lo quitaba | tomada, 2026-11-01 |
| 30 | Errores de antes         | la vista del compilador compara: es violación todo error que no estaba antes de la transacción (mismo archivo, código y mensaje, sin importar la línea). Antes, con un solo error en cualquier archivo, la transacción no se compilaba y quedaba como obligación ("types were not checked"): en un proyecto real, siempre con algo roto, un extract superclass dejaba la superclase y la subclase sin compilar | tomada, 2026-11-01 |
| 31 | Reparar sin cambiar el sentido | un efecto del conocimiento es un quick fix que la gente elige; solo uno que declara `keeps meaning` (abrir, mover adonde se usa, importar, ampliar un override, declarar el constructor que el lenguaje ya daba) puede reparar solo un refactoring. Un `Plan` dice si `changesMeaning` y el `Repairer` no lo toma. Antes cualquier fix sin preguntas podía reparar: cambiar una llamada a un método de nombre parecido, sacar un `final`, inventar un valor para los llamadores | tomada, 2026-11-01 |
| 32 | Sentencias y locales     | las sentencias de primer nivel de un cuerpo y las variables locales son elementos del grafo (`STATEMENT`, `LOCAL`), sitio de sus usos; las expresiones siguen opacas; los nueve primitivos alcanzan; `Body` dice una sentencia. El conocimiento pasa a ser reglas generales del lenguaje, sin transformaciones escritas: el motor las deduce de lo que se rompe, y un refactoring es solo una intención. Reemplaza en parte la 7 (cuerpos fuera del grafo). Sección 9, etapa 3 y su apéndice | propuesta, 2026-11-01 |
| 33 | Motor de reglas          | un producto propio, `rules`, que no depende de nada: Datalog con negación estratificada (seguro y estratificado al armarse), hechos como vistas pedidas por patrón y suponer un cambio como operación del contrato; el motor estándar evalúa a demanda. Uno maduro entra como otra implementación de `Engine`, con un adaptador y los mismos tests de contrato. El lenguaje de superficie (`def`, `say`) y el planificador quedan en `knowledge` | tomada, 2026-11-01 |
| 34 | Intenciones              | un refactoring del catálogo puede ser una intención: dice lo que tiene que valer al final (`want`, en el lenguaje de `knowledge`: hechos del grafo y `given` para las condiciones), nada de cómo. `refactoring` la guarda como texto y se la pasa al planificador de la biblioteca (`Planner`), que es el conocimiento: deduce los cambios y lo que rompen en un solo plan, sin que lo pedido cuente para el tope, y toma el más barato que conserva el sentido. El reparador queda para lo que las reglas no previeron y sigue juzgando todo plan | tomada, 2026-11-02 |

Una decisión "propuesta" se aplica salvo objeción; al aplicarse pasa a "tomada" con la fecha y el commit.

## 8. Intrusiones conocidas

Registro de deuda de diseño, con la fase que la resuelve.

| Intrusión                                                                          | Dónde                  | Fase |
|------------------------------------------------------------------------------------|------------------------|------|
| Vínculos como strings, modos como strings, firma cortando la dirección (resuelto en 4d16bfe y fase 1) | `ide.program` | 1 |
| Consultas lineales dentro de bucles (resuelto en 4d16bfe)                          | `ide.program`          | 1    |
| Comparación de referencias dentro del Verifier (resuelto en 4d16bfe)               | `ide.engine`           | 1    |
| Identidad por prefijos de string y regex; bug de rename de tipo usado como parámetro (resuelto en 67901c9) | `ide.program.Identity` | 1 |
| Interfaz mutable con nueve firmas y checkpoint sin tipo (resuelto en 1524f0c)      | `ide.projection`       | 2    |
| La Transaction interpreta atributos para deducir la intención (resuelto en 1524f0c y 5cceb1a) | `ide.engine`    | 2    |
| Registro manual de los primitivos como refactorings (resuelto en 351d5c2)          | `ide.refactoring`      | 2    |
| Dos mecanismos de edición; `changed()` reimprime todas las unidades (resuelto en 3f994c2) | `ide.java` | 3 |
| `set` como switch de operaciones (marcadores, `default` y `order` resueltos en 8f0ed6a; `import` en 0a738bf; `body` escribe el lenguaje de cuerpos desde 37c903e; queda `initializer`, que solo Java entiende) | `ide.java.primitives.SetAttribute` | etapa 2 |
| `MemoryProjection` en un solo archivo grande (resuelto en 9c507a0)                  | `projection-kit`       | 3    |
| Búsqueda de declaración, unidad y ruta repetida por primitivo (resuelto en 646c9bc) | `ide.java`             | 3    |
| Vistas no enchufables; vista del compilador dentro del reparador (resuelto en 6bbf600) | `ide.engine`       | 4    |
| Rastro como strings con sigilos (resuelto en 34a3b0e)                              | `ide.engine`           | 4    |
| Runtime de refactorings como clase anónima de la sesión; `Ctx` falso en knowledge (resuelto en 6911992) | `ide.session`, `ide.knowledge` | 5 |
| Valores de scripts como strings; id o dirección resuelto en cada función (resuelto en fb7e3e9) | `ide.refactoring.script` | 5 |
| Plantillas de cuerpo en Java dentro del catálogo e `initializer` en el conocimiento (marcadas en fb7e3e9; `set import` se fue en 0a738bf; resuelto en 37c903e y 77e0742) | `standard.ref`, `knowledge.ref` | etapa 2 |
| Convenciones de Java fuera de los drivers: `is-type-name` y el `int` que propone create-field (resueltos con los usos de miembros sin resolver y lo que esperan) | `ScriptFunctions`, `knowledge.ref` | etapa 2, ítem 1 |
| Los usos de bibliotecas no se verificaban: un rename podía capturar los usos de un import con comodín (resuelto en a5fdf89) | `ide.java`, `ide.engine` | etapa 2 |
| El reparador entra dos veces en el código que coordina (decisión 17; resuelto en 85bb676) | `ide.engine`, `ide.session` | 6 |
| La sesión carga `knowledge.ref` por su ruta y arma los hechos de un diagnóstico (resuelto en a288c2e) | `ide.session` | 6 |
| La navegación recorre el grafo a mano: overrides transitivos, supertipos, subtipos (resuelto en 39f5dda) | `ide.session` | 6 |
| Workspace como cajón de todo (resuelto en d26ed56, 4426824 y 708d184)              | `ide.session`          | 6    |

## 9. Plan de acción

### Reglas de la migración

- Estrangulación, nunca big-bang: cada commit deja todos los tests en verde. Un commit por ciclo.
- El orden sigue las dependencias, de abajo hacia arriba, con una excepción: la proyección falsa entra temprano
  (fase 2) porque habilita los tests de autonomía de todo lo demás.
- Cada fase abre con el test de producto o de contrato que la define, en rojo, y cierra con el test de autonomía
  del producto en verde. Si cambia una dependencia entre productos, el pom cambia en el mismo commit.
- Los tests de aceptación (los que invocan refactorings y comparan texto) no se tocan. Los que miran el rastro
  siguen pasando por el render. Los casos borde que pertenecen a un producto bajan a sus tests cuando la fase lo
  toca.
- `PerformanceProbe` al cierre de cada fase: el rename sobre `OrdersBig` no empeora.
- Al cerrar una fase se actualiza el "Estado actual" de las fichas tocadas y el estado de las decisiones aplicadas.

### Fase 1: `program` como producto (cerrada, 2026-10-31)

- Objetivo: grafo tipado, con vínculos, índices, diff y direcciones tipadas.
- Tests rojos que la abren:
  - ArchUnit: `program` depende solo de `core`.
  - `ProgramTest`: grafo armado a mano; `supertypes` y `overriddenAll` a partir de referencias EXTENDS e IMPLEMENTS;
    `referencesTo` por índice; `compare` devuelve perdidas, ganadas, nacidos e idos.
  - `AddressTest`: parseo y render de direcciones; la dirección de un miembro no cambia al renombrar el tipo de un
    parámetro.
- Qué se toca: `ide.graph` pasa a `program`; `ProgramGraph` pasa a `Program`; `Reader` emite vínculos como
  referencias; `Identity` se muda a `program` con la API tipada (la proyección sigue usándola por direcciones
  renderizadas hasta la fase 3).
- Criterio de cierre: no queda ningún atributo string de tipo en el grafo; el Verifier usa `Program.compare`;
  ninguna consulta escanea todos los elementos.

### Fase 2: el contrato `projection` y el fin de la interpretación en el motor (cerrada, 2026-10-31)

Hecha en siete ciclos, del 99c67c2 al 351d5c2. Además de lo planeado: `Projection` responde sus `rules()`; el
contrato encontró que el driver de Java creaba duplicados; `Link` destapó dos bugs (una superclase de fuera del
workspace se perdía al extraer una superclase, regresión de la fase 1; y reescribir un `extends` conservaba los
argumentos de tipo del anterior). Los tests de `engine` fijan comportamiento existente.

- Objetivo: `Primitive` y `Change`; `Projection` con `apply`; `MemoryProjection`; kit de conformidad; `Transaction`
  que pliega lo que el `Change` declara.
- Tests rojos:
  - Ya vigente por los poms: `engine` no declara `java`; `projection` declara solo `program`.
  - `ProjectionContract`, abstracto, con las cinco obligaciones del driver, instanciado para `MemoryProjection` y
    para `JavaProjection`. Los dos viven en un módulo nuevo, `projection-kit`: el kit de conformidad es un producto
    en sí, el que bajaría quien escribe un driver. `engine`, `refactoring` y `knowledge` lo usan en sus tests;
    `java` corre el contrato en los suyos.
  - `EngineTest` sobre `MemoryProjection`: una vista y un remedio del test; punto fijo, topes, pins.
  - `TransactionTest`: las expectativas salen del `Change`, no de nombres de atributo.
- Qué se toca: `JavaProject` implementa `apply` delegando a sus métodos actuales y declarando los `Change` (la
  lógica de intención se muda de `Transaction` a cada primitivo de Java); `Primitives.all()` se genera desde el tipo
  sellado; `Set` de tipos pasa a `Link`; `set order` pasa a `Move`; `set default` entra en `Create`.
- Criterio de cierre: `Transaction` no menciona ningún atributo; `Primitives` no registra nada a mano; el motor
  corre entero contra la proyección falsa.

### Fase 3: `java` en capas (cerrada, 2026-10-31)

Hecha en cinco ciclos, del 86422b5 al 9c507a0. La fachada quedó en unas 200 líneas y no en 150: 45 son el
contexto que ven los primitivos. Además de lo planeado: el lector numeraba cada referencia contando todas las
anteriores, y ahora es lineal. Con eso y sin printer léxico, cargar el sample grande pasó de 1,5 s a 0,25 s y el
rename de 2,1 s a 1,1 s.

- Objetivo: una sola vía de edición, un primitivo por clase, identidad tipada, fachada chica.
- Tests rojos:
  - `RenameTypeTest`: renombrar un tipo usado como parámetro en otra clase se aplica (el bug reproducido).
  - `EditSetTest`, `DeclTest`, `RenderTest`: unitarios de cada capa.
  - ArchUnit: fuera de la capa de texto nadie llama al parser ni toca posiciones.
- Qué se toca: `Unit`, `Edit`, `EditSet`; `Decl`; `Render`; una clase por primitivo; `JavaProjection` como fachada;
  se eliminan `changed()`, el printer léxico y los marcadores de defaults; `Identity` tipada en uso.
- Criterio de cierre: la fachada ronda las 150 líneas; ninguna mutación del AST; `PerformanceProbe` igual o mejor.

### Fase 4: vistas y rastro en `engine` (cerrada, 2026-10-31)

Hecha en dos ciclos, 34a3b0e y 6bbf600. `StepsTest` pasó sin cambios: `Result.steps()` son las líneas del rastro.
Los tests que leen la búsqueda de reparaciones o el `why` pasan por el render (`lines()`). Las vistas no se
configuran todavía desde la sesión: usa `Verifier.standard()`; agregar una vista es una clase y una línea ahí.

- Objetivo: `View` enchufable, `Trace` como datos.
- Tests rojos: `ViewsTest` (cada vista sola sobre dos grafos armados a mano); `TraceTest` (árbol y render);
  `StepsTest` sigue pasando sin cambios.
- Qué se toca: `Verifier` como lista de vistas; la vista del compilador sale del `Repairer`; `Result` lleva el
  árbol; `why` y `why-repair` devuelven el árbol y lo renderizan en el borde.
- Criterio de cierre: agregar una vista es agregar una clase; los strings con sigilos existen en un solo lugar.

### Fase 5: `refactoring` y `knowledge` como productos (cerrada, 2026-10-31)

Hecha en tres ciclos: e70079e (`Library`), 6911992 (`Run`) y fb7e3e9 (valores tipados). El plan llamaba `Runtime`
a lo que quedó como `Run`. La regla de ArchUnit "knowledge no tiene `Ctx` propio" no hizo falta: `Run` es una clase
final con constructor privado, no hay interfaz que implementar, y el compilador cumple la regla.

Pasar al run real destapó dos cosas que el contexto falso escondía, y cada una se fijó en su producto:

- El parser de conocimiento no contaba `try` como bloque. Ahora el lenguaje de scripts dice dónde termina un bloque.
- Volver a un punto de una transacción no olvidaba lo declarado desde entonces. Ahora el punto guarda línea base y
  pins.

Con los valores tipados, `superclass` responde un elemento, y un mensaje de collapse-hierarchy pasó a escribir un
id; la aceptación ahora lee ese mensaje.

- Objetivo: `Library` y `Run` propios; valores tipados; conocimiento sobre el run real.
- Tests rojos: `RunTest` en `refactoring`, sobre `MemoryProjection` (expand de encapsulate-field da la secuencia
  esperada; semántica de attempt y oblige); `KnowledgeTest` en `knowledge`, sobre `MemoryProjection` con efectos
  inventados.
- Qué se toca: el run sale de `Workspace`; `Knowledge` usa el run real; `Value` y `Args` resuelven id-o-dirección
  una vez; las plantillas Java del catálogo quedan marcadas como fuga conocida.
- Criterio de cierre: `Workspace` no tiene ningún `Ctx`; un script se prueba sin motor. Cumplido: `Ctx` ya no
  existe, y `RunTest` corre encapsulate-field sobre la proyección en memoria con `Run.on`.

### Fase 6: `session` (cerrada, 2026-10-31)

Hecha en seis ciclos: 85bb676 (decisión 17), a288c2e (lo que la sesión sabía de `knowledge` vuelve a `knowledge`),
d26ed56 (`Navigation` y `Completion`), 4426824 (`Fixes`), 708d184 (`Session` y `Capabilities`; la fachada delega) y
39f5dda (las preguntas de jerarquía vuelven a `program`). Además de las partes del plan apareció `Fixes`: los quick
fixes de una línea no son ni sesión ni navegación.

Leer cada clase con los términos de los otros productos encontró tres intrusiones, y cada una se arregló en el
producto dueño:

- La transacción no sabía cómo se la juzga: el reparador entraba dos veces.
- La sesión cargaba la base de conocimiento por su ruta y sabía qué hechos da un diagnóstico.
- La navegación recorría el grafo a mano.

`SessionTest` cubre una regla que ningún test cubría: un fix que deja su diagnóstico no se conserva.

- Objetivo: separar `Session`, `Navigation`, `Completion` y `Capabilities`; `Workspace` como fachada.
- Tests rojos: los actuales de `session` siguen pasando contra la fachada; un test por colaborador en aislamiento.
- Criterio de cierre: cada clase de `session` se lee con los términos de los otros productos; la fachada solo delega.
  Cumplido: `Workspace` arma las partes y cada uno de sus métodos es una delegación de una línea.

Con la fase 6 termina la migración: cada producto del mapa es un módulo con sus propios tests. Lo que sigue es la
etapa 2, por valor.

### Etapa 2, por valor

1. Referencias no resueltas en el grafo: el quick fix de import se vuelve un retarget, `binding.unresolved` y
   completion comparten modelo. Hecho para los tipos escritos, en tres ciclos:
   - a5fdf89: los tipos de biblioteca que el workspace nombra son elementos, y sus usos, referencias verificadas.
     Ganancia directa: un rename que capturaba los usos de `java.util.*` ahora es `binding.rebound`. Destapó que
     reemplazar un tipo vinculado no declaraba la ida de sus argumentos de tipo.
   - 0b40a6c: los usos que no ligan nada son parte del programa, y un retarget los liga.
   - 0a738bf: el fix de import es un retarget; se van `set import` y los candidatos de import de las reglas.

   - Usos de miembros: un campo leído o escrito, o un método llamado, que no liga nada es un uso sin resolver, con
     el tipo donde se busca. Solo cuando es seguro (decisión 26): de un valor, cuando el resolvedor dice que no hay
     campo, local ni parámetro con ese nombre; de una llamada, cuando ningún método tiene ese nombre donde se busca.
     Nunca lo que va antes de un punto o de `::` (puede ser un paquete o un tipo), una etiqueta de `case` ni un nombre
     dentro de una anotación. Sobre una copia del emulador del usuario (229 archivos sin errores) no aparece ninguno;
     la primera versión daba falsos en llamadas con lambdas sobre `Stream` y en receptores de `::`, y la que decide
     por nombre, en los métodos de `Object` sobre interfaces y en el `values()` de un enum. Un retarget lo
     liga renombrando el uso, si el destino es lo que el uso pide. El símbolo de un diagnóstico sobre `v.size` es
     `size`.

   - create-method: la proyección dice qué necesita un uso (qué tipo espera su lugar y, si es una llamada, qué pasa;
     obligación 10), y el fix crea el método con eso. Primero lo guardaba el grafo, calculado para cada uso en cada
     lectura; pasó a ser una pregunta del contrato, y create-method se prueba sobre `MemoryProjection`, sin Java.

   Abierto: la completion y los fixes todavía no comparten una sola pregunta (qué puede ligar este uso desde su
   sitio); un nombre sin resolver antes de un punto no es uso; los tipos de lo que necesita un uso se escriben como
   los nombra su archivo, aunque el método o el campo se cree en otro; un método creado en otro paquete no se abre.
2. Reconciliación estructural de identidad en ediciones manuales (hoy un rename a mano equivale a borrar y crear).
   Hecho en dos ciclos:
   - afd56af: lo que una edición a mano renombra, cambia de firma o mueve de paquete conserva su id, y lo que quita
     no vuelve. Antes un elemento borrado a mano volvía con su id viejo si se escribía de nuevo su nombre.
   - 7e7a8fd: el paso de la edición dice qué se entendió de ella; un tipo renombrado es una línea, no una por miembro.

   Abierto: con la identidad estable, una edición a mano se puede verificar contra lo que dejó roto en otros
   archivos y ofrecer seguir el rename. No se hizo todavía: verificar también el archivo editado marcaría como
   violaciones los cambios hechos a propósito; ofrecer seguir el rename ya tiene los usos de miembros sin resolver
   que necesitaba. Tampoco se reconoce un miembro llevado de un tipo o de un archivo a otro.
3. Invalidación selectiva del grafo. Hecho en d97d73b. En el sample grande (101 archivos, unas 10.000 referencias),
   la relectura después de un rename de campo resuelve 2 archivos y tarda unos 20 ms en vez de unos 190; un rename
   con el programa ya leído tarda la mitad. Lo que queda del costo es javac. Las pruebas de que una lectura parcial
   es igual a una completa ya existían: la obligación de lectura reproducible del contrato, después de cada
   primitivo, y los samples, que comparan el modelo con uno releído después de cada caso. `InvalidationTest` cubre
   la regla, con las capturas a través de un subtipo y de una sobrecarga.

   Abierto: un cambio de tipo (nombre, paquete, cabecera) relee todo, también lo que no lo nombra. El compilador ya
   es incremental (decisión 28): en el sample de 100 archivos, un segundo rename tarda unos 45 ms en total.
4. Lenguaje neutral de cuerpos para plantillas; se van las fugas de Java del catálogo. Hecho en tres ciclos:
   - 37c903e: un cuerpo se dice sin lenguaje (`Body`, en `program`) y cada proyección lo escribe en el suyo. La de
     memoria hace los usos que nombra; Java escribe nombres, califica un campo que un parámetro tapa e importa el
     tipo de un objeto nuevo. Los scripts nombran elementos con `#{expr}`. Los cuerpos de getter, setter y factory
     ya no tienen Java, y el setter perdió la rama de los estáticos, que era una decisión de Java.
   - 05de013: lo que un cuerpo nombra es lo que el cuerpo escrito tiene que ligar. Un `Set` de cuerpo declara los
     usos del viejo como idos y los del nuevo como agregados; la vista de ligaduras compara. Un nombre que el
     lenguaje resuelve a otro elemento ya no es una captura callada sino `binding.rebound`: el caso de prueba es una
     clase interna que tapa un campo de la externa, que antes se aplicaba ligando el de la interna.
   - 77e0742: cómo escribe un lenguaje el valor de un campo sin asignar es una pregunta a sus reglas. El fix de un
     `final` sin inicializar escribe lo que dice el usuario.

   Abierto: el lenguaje tiene solo lo que el catálogo pide (no hay llamadas, literales ni variables locales); Java no
   escribe `Outer.this.x`, y ese caso se rechaza; quedan dos convenciones de Java fuera de los drivers (sección 8).
5. Lo que falta del catálogo: pull-up-unifying, borrado de tipos anidados, encadenamiento en varios niveles del
   conocimiento. Hecho en cuatro ciclos:
   - ffb46e6: Java borra tipos anidados, así collapse-hierarchy anda sobre una clase anidada. Colapsar una destapó
     dos bugs, cada uno arreglado donde vivía. Retargetear el tipo escrito en un parámetro le cambiaba la dirección
     al método sin llevarle la identidad, en los dos drivers; ahora la obligación 1 del contrato incluye retarget.
     El otro: se importaba un tipo que el mismo archivo declara.
   - 8eb59b1: un miembro borrado "dentro de" otro se fusiona en él (decisión 20). Fusionar un override redundante en
     el método que sobrescribe fallaba: la llamada se lee igual y seguía ligando el override.
   - 3909198: pull-up-unifying, y la regla de despacho de la decisión 21. Un test de aceptación esperaba la regla
     vieja (subir un método abortaba si un hermano tenía la misma firma) y ahora espera que el hermano lo sobrescriba.
   - 8695c28: el conocimiento encadena hacia atrás (decisión 22). El caso real: importar un tipo package-private de
     otro paquete dejaba un error de acceso; ahora el fix lo abre antes.

   Abierto: fusionar tipos (un `delete` de un tipo `into` otro) no existe, y collapse-hierarchy sigue retargeteando y
   borrando; `invariant` del conocimiento; de la lista de la spec faltan los refactorings que nombra la ficha de
   `refactoring`.
6. Adaptadores: CLI, luego UI, luego MCP, cada uno como módulo que declara solo `session` y `core`. Hecho en siete
   ciclos:
   - b6acdb2: `core` lee JSON.
   - 5e8c928: un workspace se abre desde una carpeta, registra sus eventos ahí y guarda ahí; `save` es capacidad.
   - cedbd23: la CLI.
   - 3223bf9: posiciones en el contrato de la proyección (obligación 9), respondidas por el driver de Java.
   - f7036cb: las capacidades de un editor (`at`, `where`, `elements`, `edit`, `asks`, respuestas de `fix`).
   - 763d4e6: la UI.
   - 8b760b3: el servidor MCP.

   Abierto: lo de la UI que lista la ficha de adaptadores; el build incremental y el runner de la spec.

### Etapa 3 (propuesta): sentencias y locales en el grafo

Propuesta del 2026-11-01, para discutir antes de escribir código (decisión 32, propuesta).

**El diagnóstico.** Lo específico de cada quick fix, refactoring o pasito intermedio está solo en los `.ref`: ningún
Java nombra un fix ni un refactoring. Se deduce cuándo aplicar una regla y cómo encadenarlas. Ejemplo: el script de
extract-superclass no sabe nada de constructores, y el reparador encuentra solo que subir un `final` pide
`create constructor AbstractUnset(), then pass limit to the constructor of AbstractUnset`. Pero cada regla es una
receta escrita a mano, y cada receta nueva choca con algo que el sistema no sabe decir. De los 13 commits de la mañana
del 2026-11-01, lo específico fueron unas 40 líneas de `knowledge.ref`. El resto fue:

- vocabulario: `...` y `#{constructor}(...)` en el lenguaje de cuerpos, la pregunta `said` del contrato,
  `value-given` y `said-without` en los scripts;
- bugs del driver que el vocabulario destapó: `new`, `super(...)` y `this(...)` no eran llamadas;
- seguridad del motor: decisiones 30 y 31, y que una reparación se juzgue por lo que quiso.

La causa es una sola: **el grafo llega hasta los miembros**. Dentro de un cuerpo el sistema entiende solo lo que
dice `Body`, que no tiene locales, literales ni operaciones. Todo pasito que ocurre dentro de un método (mover una
asignación, pasar un valor por `super`, sacar una expresión a una variable) necesita vocabulario nuevo. Cuando el
vocabulario no alcanza, la regla no se ofrece y el refactoring se rechaza (`this.limit = limit * 2`). Las señales de
que esto no escala solo:

- `ScriptFunctions` ya tiene 65 funciones, varias angostas: `value-given`, `said-without`, `unimplemented`.
- `pass-to-superclass` es una receta de 15 líneas que hace tres cosas distintas.
- El fragmento de `Extract` es una dirección opaca (`L-L`, `L:C-L:C`).
- Extract Variable, Inline Variable e Introduce Parameter no se pueden hacer: los locales no existen.

**Las opciones.**

- *A, seguir como hasta ahora:* el lenguaje de cuerpos y el contrato crecen con cada regla. Es barato por regla,
  pero las reglas siguen siendo recetas grandes, y cada pregunta nueva del contrato la tienen que responder los dos
  drivers.
- *B, el árbol entero en el grafo:* cada expresión es un nodo con identidad. Lo descarto. La identidad de una
  expresión no sobrevive una edición a mano. El diff del verificador explota (en el sample grande, de unas 10.000
  referencias a cientos de miles de nodos). Y el grafo dejaría de ser estructura para ser el AST de un lenguaje,
  contra el principio 1.
- *C, recomendada: sentencias y locales como elementos; las expresiones siguen opacas.* Es el nivel donde ocurren
  los pasitos de los refactorings: una sentencia se mueve, se borra o se crea, y un local se declara, se lee y se
  escribe. Lo que hay dentro de una expresión sigue siendo de la proyección, como hoy.

**La opción C.**

- *`program`.* Dos `Kind` nuevos.
  - `LOCAL`: una variable local o de un `for`, hijo de su método o constructor, con dirección por dueño y nombre
    (como un parámetro), ambigua si dos bloques reusan el nombre.
  - `STATEMENT`: una sentencia del primer nivel de un cuerpo, hijo ordenado de su método o constructor (como los
    parámetros). Lleva el hecho `BODY` con lo que dice en el lenguaje de cuerpos, cuando se puede decir. Si no se
    puede, es opaca, pero igual tiene identidad, posición y usos.

  El `site` de una referencia pasa a ser el elemento más interno que la contiene (la sentencia o el local), y lo
  que hoy pregunta por el miembro lo responde `within` (que ya existe). Un uso de un local es una referencia como
  cualquier otra, verificada por las mismas vistas.
- *Primitivos.* Ninguno nuevo: los nueve alcanzan con los `Kind` nuevos (principio 3).
  - `Create(STATEMENT, método, antes de, cuerpo dicho)`, `Delete(sentencia)` y `Move(sentencia, método, antes de)`,
    también entre métodos.
  - `Create(LOCAL, ...)`, `Rename(local)` y `Retarget(uso, local o parámetro)`.
  - `Extract` toma una lista de sentencias en vez del fragmento opaco y dice qué produce: `METHOD` o `LOCAL`.
  - El fragmento opaco queda solo para lo que una persona selecciona dentro de una expresión.
- *Cuerpos.* `Body` pasa a decir una sentencia, no un cuerpo entero.
  - Un `Set` de cuerpo se vuelve `Delete` y `Create` de sentencias.
  - `...`, `said`, `value-given` y `said-without` se van: cada sentencia se dice sola o es opaca.
  - La obligación 11 se reemplaza por "cada sentencia dice lo que hace si puede".
- *Conocimiento.* Deja de tener transformaciones escritas. Es lo que Java exige de un programa, en reglas
  generales que no saben de refactorings ni de quick fixes ("un final lo asignan solo los constructores de su
  tipo", "una llamada da un argumento por parámetro"). Las únicas acciones son los primitivos, con lo que cada uno
  cambia en el grafo, y eso es fijo y no depende del lenguaje. El motor toma una regla que no se cumple, prueba los
  primitivos que cambian algo de lo que la regla mira, ve qué se rompe después y sigue. Cada camino que termina
  coherente es una propuesta, y varios caminos son varios fixes. Si el camino pasa los invariantes de sentido y no
  toca ninguna promesa del lenguaje (`final`, el `static` de un campo, la firma de algo público), conserva el sentido
  y se aplica solo; si no, se ofrece. Así `keeps meaning` deja de declararse a mano y se deduce. Un refactoring
  queda reducido a la intención de la persona (`want`), y lo demás se deduce. El compilador sigue siendo el juez:
  una regla incompleta pierde un camino, y una equivocada da un candidato que el verificador rechaza. Cómo quedaría
  todo esto está en el apéndice de esta etapa.
- *Identidad de las sentencias.* Es lo difícil.
  - Una sentencia no tiene nombre.
  - Los primitivos saben dónde dejan cada sentencia, así que conservan su id sin ayuda.
  - Después de una edición a mano, la reconciliación las empareja por orden y por texto (subsecuencia común más
    larga), como hoy empareja miembros: solo cuando no hay dudas. Si hay dudas, la sentencia es nueva, lo que es
    seguro: el verificador la juzga como agregada.

**Costos y riesgos.**

- El grafo crece con las sentencias: en el sample grande, unas 5 a 10 veces más elementos. La invalidación
  selectiva relee por archivo, así que el costo va a la lectura completa, que se mide en `PerformanceProbe`.
- Cambiar el `site` de las referencias toca las vistas, la navegación y los tests que miran sitios.
- Los dos drivers responden obligaciones nuevas (locales, sentencias, sentencias dichas). `MemoryProjection` tiene que
  modelar cuerpos como listas de sentencias.
- La reconciliación de sentencias en ediciones a mano es nueva.

**La migración**, por estrangulación y con un commit por ciclo, como las fases anteriores:

0. *Experimento, sin sentencias.* Los cinco efectos que hoy conservan el sentido y no necesitan sentencias (abrir
   visibilidad, subir una dependencia, importar, ampliar un override y declarar el constructor) se reemplazan por
   reglas de acceso, alcance y construcción, junto con el motor que deduce las acciones de lo que se rompe. El
   criterio: los mismos tests de aceptación pasan con planes deducidos y sin efectos escritos. Si no sale ahí, donde
   el problema es más fácil, no va a salir con sentencias.

   **Hecha el 2026-11-01, en seis ciclos** (d12602f..335ba3d). Salió:
   - Los cinco efectos se fueron. `knowledge.ref` pasó de 25 efectos a 20, y `java.rules` tiene 16 definiciones de
     Java y ningún fix: quién puede usar qué (JLS 6.6), dónde se usa un miembro sin decir de quién es (8.2), el orden
     de los inicializadores (8.3.3), la visibilidad de un override (8.4.8.3), qué nombra un tipo escrito (6.5.5) y
     el constructor que da el lenguaje (8.8.9). `ScriptFunctions` perdió `can-access` y `subclass-of`.
   - Los 146 tests de aceptación pasan, con los mismos títulos y el mismo rastro. Cambiaron dos expectativas, y las
     dos para mejor: un move de tipo ya no prueba `protected` donde no alcanza (antes lo probaba a ciegas y el
     verificador lo rechazaba), y una definición que no aplica queda en el `why` con el literal donde se trabó.
   - Lo fijo, sin lenguaje: `rules` (Datalog) y, en `knowledge`, `GraphFacts` (el grafo como relaciones) y
     `Abduction` (qué primitivo cambia qué relación: visibilidad, padre, lugar, a qué liga un uso, y un constructor
     que nadie tiene se crea).

   Lo que enseñó, para las fases que siguen:
   - La abducción lee una definición de izquierda a derecha, como Prolog: lo que se pregunta con argumentos sin
     valor se consulta, y lo que se cambia tiene que ir después de lo que le da valor. La evaluación (si algo vale)
     es Datalog puro y no depende del orden; la búsqueda de cambios sí. Una mejora posible es postergar un hecho
     cambiable hasta que otro le dé valor, y probar los dos lados de una comparación.
   - Datalog exige que cada variable tenga de dónde salir: `accessible(site, m) when visibility(m, "public")` no es
     válido, y hace falta decir qué es `site` (`kind(site, _)`). Es más preciso, y obliga a escribir lo que antes
     quedaba implícito.
   - Detectar sigue siendo de las vistas y del compilador. `AccessView` usa `JavaRules.accessible` (código Java en
     el driver), que dice lo mismo que `java.rules`: está dos veces. Lo siguiente es que el conocimiento aporte una
     vista que evalúe sus reglas, y que el driver deje de saber de acceso.
   - Que lo deducido conserve el sentido se supone para estos cinco cambios (abrir, subir, importar, declarar lo que
     el lenguaje ya daba). Todavía no hay promesas: hacen falta antes de deducir cambios que sí podrían romperlo.
   - Lo que una definición necesita de otra se supone hecho al leer lo que sigue, sin volver a evaluarlo.

   Antes de las sentencias quedan efectos que tampoco las necesitan y que la misma máquina puede reemplazar. Los
   estáticos ya pasaron (el 2026-11-01, 37279f6..): hizo falta que un uso diga sobre qué se hace (`Reference.receiver`,
   obligación 12), restricciones (`rule`, `problem`), abducir hechos que se quitan, revisar las reglas después de
   cada cambio supuesto y promesas. Después pasaron `make-abstract`, `unfinal`, `drop-final` y los dos cambios de
   tipo (38b5704..ea3354e): `knowledge.ref` tenía entonces 14 efectos, de los 25 del principio. Con la creación de
   miembros con forma y las preguntas (7cd09fe..) quedan 6. Lo que sigue es lo que se escribió en aquel momento, con
   lo que ya se hizo marcado:
   - *Crean miembros o tipos con forma*: hecho, salvo `declare-in-superclass`, que necesita saber qué dice
     `@Override` (las anotaciones no están en el grafo).
   - *Necesitan un valor o un tipo que solo la persona sabe*: hecho, con `ask`.
   - *Hablan de sentencias* (`initialize-in-constructors`, `pass-to-superclass`): esperan la fase 1 de esta etapa.
   Y dos que eligen por parecido (`use-similar`, `call-similar`) y uno que borra (`remove-unused`): el parecido de
   nombres puede ser una relación del grafo, y borrar lo que nada usa, quitar un hecho de existencia.
1. *Locales.* `LOCAL` y sus usos en el grafo, con obligación del contrato. Lo que gana: rename de locales verificado
   (y el rename en el lugar de la UI), Inline Variable.

   **Ciclo 1 hecho el 2026-11-01**: los locales y sus usos están en el grafo, en los dos drivers (obligación 13).
   - Dos locales del mismo nombre en bloques hermanos no son ambiguos, como decía la propuesta: son dos, numerados
     en el orden en que se escriben (`i`, `i'2`). Así cada uso liga uno solo y el verificador los compara como a
     cualquier otra referencia.
   - El `site` de un uso de un local sigue siendo el método. La sentencia como `site` es de la fase 2 (pregunta
     abierta 2).
   - Los usos de un parámetro siguen sin ser referencias: `Body` los trata como valores, y renombrar un parámetro
     todavía no renombra sus usos.
   - Lo que el verificador encontró al entrar los locales fue extract: movía usos de locales sin decirlo. Ahora lo
     dice, y lleva la identidad de los locales que se mudan con el fragmento.
   - Costo: nada que se mida. En el sample de 100 archivos, la carga pasó de 172 a 185 ms y el segundo rename de 47
     a 46 ms (`PerformanceProbe`, ruido incluido).

   **Ciclo 2 hecho el 2026-11-01**: el rename de un local, verificado, también en el lugar desde la UI (Shift+F6).
   - La captura no necesitó código: un local que pasa a llamarse como un campo que el método lee después, o como
     otro local en alcance, hace que esos usos liguen otra cosa, y el verificador lo rechaza como `binding.rebound`.
   - Lo único nuevo fue la numeración. Tomar el nombre de un local de un bloque hermano es válido en Java, y corre
     los números de los dos nombres. `Identity.placeLocals` ubica todos los locales de un método a la vez, y extract
     usa lo mismo.
   - En la proyección en memoria no hay bloques: un local que toma el nombre de otro de su método es un duplicado.

   **Ciclo 3 hecho el 2026-11-01, y con él la fase 1**: Inline Variable (`inline-variable`, el mismo script que
   inline-method: cada lectura, después el local), también desde la tecla de Inline de la UI cuando el cursor está
   en un local.
   - Lo que el verificador ve lo sigue viendo él. Inline dice que los usos del valor se hacen donde estaba la lectura,
     y un nombre que ahí liga otra cosa (un local del ciclo que tapa un campo) es un `binding.rebound`.
   - Lo que no ve lo rechaza el driver: que un valor cambie sin que cambie ningún vínculo. Es lo mismo que ya hacía el
     flujo de extract.
   - Un id de referencia es `site@ordinal`, así que inlinear una lectura renumera las que siguen en su método. El
     script va de la última a la primera (`reversed`). inline-method tenía el mismo bug escondido: con dos llamadas en
     un método y un cuerpo que usa dos cosas, la segunda se rechazaba como "no es una llamada".
   - La proyección en memoria no tiene el valor de un local: `Inline` de un local es solo de Java, y no hay obligación
     del contrato para él.
   - Queda para la fase 2: que el `site` de los usos del valor de un local sea el local, como el de un campo, o la
     sentencia (pregunta abierta 2); que los usos de un parámetro sean referencias.
2. *Sentencias.* `STATEMENT` de primer nivel, ordenadas, como sitio de sus referencias. `Create`, `Delete` y `Move` de
   sentencias, y `Extract` por lista de sentencias. Lo que gana: el fragmento de extract-method deja de ser opaco.

   **Ciclo 1 hecho el 2026-11-01**: las sentencias son elementos, en los dos drivers (obligación 14).
   - Una sentencia es una del primer nivel de un cuerpo, hija de su método o constructor, con dirección por dueño y
     lugar (`a.A#total()[2]`) y sin nombre.
   - Conservan su id en los primitivos que cambian la lista de un cuerpo: extract las lleva al método nuevo y la llamada
     ocupa su lugar; borrar un local se lleva su sentencia; un cuerpo escrito de nuevo, o un método que se vuelve
     abstracto, olvida las suyas (y sus locales). Después de una edición a mano, por orden y texto.
   - El `site` sigue siendo el miembro (ver la pregunta 2).
   - `inside` de la proyección responde lo más interno, una sentencia o un local también, como pide la obligación 9.
     Lo que preguntan los adaptadores (`inside` de la sesión) y las reglas (`inside(path, line, column)` de los
     scripts) es el miembro (`Program.memberOf`). Eso ya pasaba con los locales desde la fase 1 sin que un test lo
     viera: con el cursor en la declaración de un local, `inside` respondía el local.
   - Costo, medido con `PerformanceProbe` en el sample de 100 archivos: la carga, dentro del ruido (186 a 330 ms
     con y sin sentencias); el segundo rename, de 48 a 54 ms sin sentencias a 54 a 94 ms con ellas. Crece lo que es
     proporcional a todos los elementos: armar el `Program` en cada lectura, el diff y las vistas. Hay que mirarlo, y
     también la memoria en un proyecto grande, donde las sentencias son cientos de miles de elementos más.

   **Ciclo 2 hecho el 2026-11-01**: cada uso hecho en un cuerpo dice en qué sentencia está (`Reference.statement`, y
   lo mismo un uso que no liga nada), en los dos drivers.
   - `Program.referencesIn(sentencia)` son sus usos.
   - `UsesGone` de una sentencia se lleva los usos hechos en ella, aunque su `site` sea el método.
   - El outline escribe dónde está un uso en el cuerpo (`a.A#total() -> a.A#m(int) (call) in [2]`), así la
     obligación 3 también lo verifica después de cada primitivo.
   - En la proyección en memoria, un uso escrito desde una sentencia (`.use("a.A#total()[2]", ...)`) se hace desde su
     método y en ella. Un cuerpo dicho hace los usos de cada sentencia en ella. Lo que extract en memoria lleva al
     método nuevo no queda en ninguna sentencia, porque ese método todavía no tiene.

   **Ciclo 3 hecho el 2026-11-01**: `Extract` toma sentencias por sus ids, en los dos drivers. Se mudan al método
   nuevo con su identidad y con los usos hechos en ellas, y una sentencia que lo llama ocupa su lugar. El contrato ya
   extrae del fixture sin un fragmento en los términos de cada driver. Las líneas y el tramo de una expresión quedan
   para lo que selecciona una persona.

   **Ciclo 4 hecho el 2026-11-01, y con él la fase 2**: `Create`, `Delete` y `Move` de sentencias, en los dos drivers.
   - Ningún primitivo nuevo. Una sentencia se crea dicha en el lenguaje de cuerpos al final del cuerpo, y `Move` la
     pone antes de otra; componer es mejor que darle a `Create` un lugar.
   - La intención la dice `program`, sin lenguaje: crear dice los usos que la sentencia nombra (`Added`, así una
     captura es un `binding.rebound`); mover a otro método, que sus usos son del otro (`Resited`); borrar se lleva los
     usos hechos en ella (`UsesGone`).
   - Java escribe la sentencia como un cuerpo que conserva lo que el método hace, y la mueve o la borra por sus líneas,
     con su comentario.
   - Lo que queda afuera: mover una sentencia que declara locales (tendrían que ir con ella; se rechaza), y una
     sentencia que no está sola en sus líneas.
   - Mover o borrar una sentencia puede cambiar lo que hace el programa sin cambiar ningún vínculo (un orden de
     escrituras). Pasa lo mismo que con un cuerpo escrito de nuevo: esos primitivos cambian lo que un refactoring o un
     fix quiere cambiar, y quien sabe si se conserva el sentido es el conocimiento (`keeps meaning`), no el primitivo.
3. *Sentencias dichas.* `BODY` por sentencia. Se van `said`, `value-given`, `said-without` y `...`.

   **Ciclo 1 hecho el 2026-11-01**: cada sentencia dice lo que hace en el lenguaje de cuerpos cuando todo en ella se
   puede decir, y si no, es opaca (un local, un operador, un literal).
   - No es un hecho del grafo, como proponía la etapa. Es una pregunta al driver, `said(sentencia)` (la obligación
     11, ahora por sentencia), igual que `needs` e `inside`: lo que dice depende de a qué liga cada nombre, que se
     resuelve al leer los usos, y guardarlo en el elemento lo dejaría viejo cuando cambie un vínculo.
   - Java dice una sentencia con lo mismo que decía un cuerpo entero (`SaidBody`), y escribe una al final de un cuerpo
     sin pasar por `...` (`WriteBody.append`).
   - En la memoria, una sentencia dice lo que se le dio al crearla, y las de un cuerpo escrito de nuevo dicen la suya.

   **Ciclo 2 hecho el 2026-11-01**: el conocimiento y el catálogo trabajan con sentencias, y se fueron `...`, `said`
   (de un elemento y de un cuerpo entero), `value-given` y `said-without`.
   - Agregar al final de un cuerpo es crear una sentencia (`create STATEMENT ${c} "" facts="body=#{f} = #{p}"`):
     `initialize-in-constructors` y `generate-constructor`.
   - `pass-to-superclass` busca la sentencia del constructor que da el valor al campo (`giving`), crea la llamada al
     constructor de la superclase con lo que daba (`value`) y borra la asignación, en vez de volver a decir el
     cuerpo entero.
   - Una sentencia que llama a un constructor se crea primera: el lenguaje de cuerpos ya decía que esa llamada va
     primero, y Java no puede leer un `super(...)` en otro lugar (el plan fallaba con un error de sintaxis).
   - `ScriptFunctions` cambió tres funciones angostas por tres sobre sentencias (`statements`, `giving`, `value`).
     Un valor dado por nombre entre comillas (`facts="..."`) se lee sin ellas, como uno dado por posición.

   **Ciclo 3 hecho el 2026-11-01, y con él la fase 3**: un cuerpo son sus sentencias, y `Set` de cuerpo se fue.
   - El catálogo llena los métodos que crea (getter, setter, factory, delegados) creando sentencias. Un método creado
     tiene el cuerpo vacío.
   - El conocimiento hace que un método creado responda el valor inicial de su tipo con una sentencia (`return
     default`); uno `void` no responde nada, y crear `return default` en él se rechaza, porque en Java no dice nada.
   - En los dos drivers se fueron el cuerpo entero de `Set`, su intención (`Dropped` de lo viejo, `Added` de lo
     nuevo) y el código que lo escribía. Lo que un cuerpo escrito de nuevo hacía es borrar sus sentencias y crear
     otras.
   - El rastro de un paso dice lo que dice una sentencia creada (`create STATEMENT a.P#getX()[1] = return
     #{field:2}`), como antes decía el cuerpo que se escribía.
   - Lo que queda del lenguaje de cuerpos dice una sentencia a la vez. Sigue siendo una lista, la de una sentencia,
     por si una regla necesita decir varias juntas.
4. *Conocimiento como reglas.* Los efectos de `knowledge.ref` se reemplazan, de a uno, por reglas del lenguaje
   (finales, construcción, llamadas, estáticos, abstractos); los scripts del catálogo pasan a ser intenciones.

   **Ciclo 1 hecho el 2026-11-01**: elegir por parecido. `use-similar` y `call-similar` se fueron; quedan 4 efectos.
   - El parecido de nombres es una relación del grafo (`similar(e, tipo, nombre, kind)`: un miembro que el tipo
     tiene o hereda, a una o dos letras, como una errata, el más cercano primero), y no una función de los scripts.
   - `java.rules` dice que un nombre mal escrito resuelve en el miembro casi igual que es accesible, y que cambiar el
     uso a él es ligarlo (`target`): el mismo cambio que ya hacía `import`. Cada forma tiene su predicado
     (`spelled`, `called-as`), así cada una tiene su título.
   - El rastro de `why` ya no muestra esos efectos aparte: sus caminos son definiciones de `resolves`, tratadas en
     orden con las otras.

   **Ciclo 2 hecho el 2026-11-01**: borrar lo que nada usa. `remove-unused` se fue; quedan 3 efectos.
   - Existir es un hecho del grafo (`exists(e)`) que solo un primitivo quita, borrando el elemento: es la primera
     relación que la abducción puede quitar y nunca poner.
   - `java.rules` dice que un miembro privado, que nada de afuera de su tipo puede usar, se usa o no está
     (`used-or-gone`). Si nada lo usa, lo que queda es que no esté, y se ofrece "remove ...".
   - Que de verdad nada lo use lo dice el compilador (es su advertencia), y si algo lo usa el borrado se rechaza.

   **Ciclo 3 hecho el 2026-11-01**: dar valor a un `final` en los constructores, deducido de `final-once`.
   `initialize-in-constructors` se fue; quedan 2 efectos.
   - La abducción aprendió a sacar el apoyo de una regla haciendo verdadero un predicado definido que la regla
     necesita falso: `not given-by(k, f)` se deshace con las definiciones de `given-by`, leídas desde el estado
     supuesto.
   - Una definición nueva dice cómo un constructor da el valor: toma un parámetro del tipo del campo, al que cada
     llamada le pasa lo que ese tipo vale al empezar, y lo guarda (`stores(p, k, f)`, una sentencia que el plan crea).
     Una clase sin constructores declara uno que lo hace. De ahí salen "create constructor Q(), then initialize
     limit in the constructors" e "initialize limit in the constructors", con los mismos títulos que tenía el efecto.
   - Un plan mide sus pasos, no sus cambios: un elemento creado y lo que se le hace es uno, como lo muestra. Un título
     repetido seguido se dice una vez.
   - Crear un parámetro cambia el sentido: cada llamada pasa un valor que inventa el plan. Se ofrece como arreglo, y
     nunca repara solo (decisión 31). Lo encontraron make-final y la garantía: el reparador inicializaba en los
     constructores lo que nadie inicializaba, en vez de deshacer el refactoring.

   **Ciclo 4 hecho el 2026-11-01**: el `super(limit)` sale de las reglas. `pass-to-superclass` se fue; queda 1 efecto.
   - Una definición de `java.rules` dice cómo un constructor que asigna un `final` de su superclase le pasa el valor a
     un constructor de la superclase, uno que no toma nada más (y que se declara si no hay ninguno). Ese constructor lo
     toma y lo guarda (`stores`, como en el ciclo 3). La llamada lo pasa primero, y la asignación se va (`passes(p, s,
     f)`). Para saber qué da la asignación, el grafo pregunta qué dicen las sentencias (`assigns(s, f)`).
   - Si conserva el sentido lo deduce el motor. Un parámetro nuevo lo conserva si el mismo plan escribe la llamada que
     le pasa el valor real, y nadie más puede llamar a ese constructor: uno existente que ninguna llamada nombra, o uno
     que el plan crea en un tipo que nada usa como tipo (solo `extends`). Un `super()` implícito de otra subclase no lo
     ve el grafo, pero deja de compilar, y eso lo ve el verificador. Por eso repara el extract superclass, y lo mismo
     ofrecido a mano también conserva el sentido.
   - Al hacer verdaderos los predicados definidos aparecieron arreglos absurdos: mover el campo a la subclase, o
     adentro de un constructor, para que `may-assign` valga. Quién puede asignar un `final` no mueve el campo: en esas
     definiciones el lugar del campo quedó como condición (`given`).
   - Un caso nuevo en `FinalFieldQuickFixTest` lo muestra a mano. Sus arreglos son "make limit not final" y "create
     constructor Base(), then pass limit to the constructor of Base", y el segundo deja `super(limit)`.

   **Ciclo 5 hecho el 2026-11-01**: un método que dice sobrescribir se declara en su superclase, deducido.
   `declare-in-superclass` se fue, y `knowledge.ref` ya no tiene efectos: solo metas.
   - `java.rules` dice que la clase que se extiende declara un método como el que dice sobrescribir: de su tipo,
     igual de visible, con los mismos parámetros (`same-parameters`, que solo toma un método que el plan crea),
     abstracto. Una regla nueva dice que una clase que declara un método abstracto es abstracta (JLS 8.1.1.1).
   - La promesa "abstract of class" ahora se mantiene mientras valga `no-new-abstract` (`promise ... kept by
     no-new-abstract`). Si nada crea objetos de la clase, volverla abstracta no cambia nada, y puede pasar como
     consecuencia de otro cambio. Si algo los crea, la regla se rompe y el camino muere, como ya pasaba.
   - Por eso el título dice todo lo que hace: "declare size() in Base, then make Base abstract".
   - Un predicado definido no puede llamarse como una relación del grafo: el motor los conoce por nombre, y
     `overrides(símbolo)` tapó a `overrides(m, n)` (la regla de visibilidad de un override se quedó sin caminos).
     Ahora es `overrides-one`.
   - Un método creado abstracto no responde nada: no tiene cuerpo.

   **Propuesta para la segunda parte (2026-11-01, para discutir antes de escribir código): el catálogo como
   intenciones.** Hoy un script del catálogo es una receta de pasos, y lo que los pasos rompen lo arregla el reparador
   después. Una intención dice lo que la persona quiere que sea cierto al final, como hechos del grafo (`want`), y el
   motor deduce los primitivos y lo que rompen en un solo plan, como ya hace con un quick fix. Lo que dice el `want`
   queda fijo: nada del plan lo deshace, como hoy los pins.

   *Ejemplo 1, pull-up-field.* Hoy:

   ```
   refactoring pull-up-field "Moves a field to the superclass of its type"
     param field : element(FIELD) "the field to move up"
     require nonempty(superclass(owner(field))) "${qualified(owner(field))} has no superclass"
     move ${field} ${superclass(owner(field))}
     return ${field}
   end
   ```

   Como intención:

   ```
   intent pull-up-field "Moves a field to the superclass of its type"
     param field : element(FIELD) "the field to move up"
     want given parent(field, t) and given superclass(t, u) and parent(field, u)
   end
   ```

   Para el `limit` de `Unset`, el motor deduce un plan: mover `limit`, abrir su visibilidad (`accessible`), y crear el
   constructor de la superclase que lo toma y que `Unset` llama con `super(limit)` (`final-where`, `given-by-its-type`).
   Es lo mismo que hoy hacen el script y el reparador en dos tiempos. El `require` desaparece: sin superclase, el
   `given` no vale y no hay plan, y el rastro dice por qué.

   *Ejemplo 2, extract-superclass.* Hoy son 8 pasos con un `if` y un `for`. Como intención:

   ```
   intent extract-superclass "Creates a class between a type and its superclass and pulls fields up into it"
     param type : element(CLASS) "the type to extract from"
     param name : text "name of the new superclass" default "Abstract${name(type)}"
     param fields : elements(FIELD) "fields to pull up" from "fields(type)"
     want given parent(type, p) and member(b, p, name, "class") and in-between(b, type) and superclass(type, b)
       and each f in fields: parent(f, b)
   end

   # in java.rules: b extends what t extended, if t extended something
   def in-between(b, t) when superclass(t, s) and superclass(b, s)
   def in-between(b, t) when kind(t, "class") and not extends-something(t)
   ```

   *Cómo se reparte el catálogo de 32.*
   - *Intenciones sobre la estructura* (lugar, vínculos, visibilidad, nombres): pull-up-field, pull-up-method,
     push-down-member, move-type, rename-package, collapse-hierarchy, extract-superclass, extract-interface,
     make-final, make-static, generalize-declared-type, rename-method-family, rename-field-with-accessors. Es lo que el
     motor ya sabe cambiar.
   - *Generadores* (crear miembros con lo que hacen): generate-getter, generate-setter, generate-constructor,
     generate-delegates, copy-signature, declare-abstract-in-superclass, introduce-factory. Necesitan que el `want`
     nombre lo que crea (`get${cap(name(field))}`) y lo que dicen sus sentencias.
   - *Usos redirigidos*: encapsulate-field(s), redirect-uses, merge-duplicate, rename-with-delegate, pull-up-unifying,
     safe-delete. Necesitan cuantificar sobre usos ("cada lectura de f fuera de su tipo liga el getter").
   - *De cuerpo*: inline-method, inline-variable, extract-method, introduce-constant, introduce-field. El flujo de
     datos es del driver (`Extract`, `Inline`), y estos siguen siendo scripts cortos sobre esos primitivos.

   *Lo que hay que decidir.*
   1. *El lenguaje del `want`.* Hechos del grafo, `given` para las condiciones y `each x in lista:` para cuantificar.
      ¿Alcanza para los generadores y los usos redirigidos, o conviene empezar solo por los de estructura?
   2. *El presupuesto.* Hoy un plan tiene a lo sumo 4 pasos, y una intención con 5 campos ya lo pasa. Propuesta: lo
      que pide el `want` no cuenta, y el tope queda para lo que se deduce para arreglar.
   3. *Varios caminos que conservan el sentido* (abrir a `protected` o a `public`, por ejemplo). Propuesta: el más
      barato; si empatan, se le pregunta a la persona, como hace IntelliJ.
   4. *El reparador.* Queda para lo que las reglas no previeron: un error del compilador que ninguna regla dedujo.
      Sigue siendo el juez de todo plan.
   5. *Por dónde empezar.* Propuesta: pull-up-field y extract-superclass. Son los dos que hoy necesitan al reparador
      para quedar bien, y extract-superclass es el caso del `super(limit)`.

   *Decidido el 2026-11-02*: las cinco como se propusieron, empezando por las intenciones sobre la estructura
   (decisión 34).

   **Ciclo 1 hecho el 2026-11-02**: pull-up-field es una intención.
   - El lenguaje `.ref` tiene `intent`: el mismo encabezado y los mismos parámetros que un refactoring, y un solo
     `want`, que sigue en las líneas que empiezan con `and`. El script lo guarda como texto: está en el lenguaje del
     planificador, que el script no lee. Correrlo es pedirle al planificador de la biblioteca que lo haga valer
     (`Planner`, en `refactoring`; `Run.want`); sin planificador, se rechaza (`intent.unplanned`).
   - El planificador es el conocimiento, y `Knowledge.standard(library)` se registra en la biblioteca. Lee el `want`
     como el cuerpo de una definición, con los parámetros ligados, y lo hace valer por abducción, como un quick fix.
     Lo que eso rompe se repara en el mismo plan, y los cambios del `want` no cuentan para el tope de pasos. Toma el
     plan más barato que conserva el sentido y no pregunta nada, y lo corre en el `Run`. Dentro de una transacción,
     sus primitivos quedan bajo la llamada, y cómo lo dedujo va a la búsqueda del resultado (`Run.searched`); un
     cambio que pidió el `want` se dice con el nombre de la intención.
   - Si las reglas no saben reparar lo que el `want` rompe, se hace el `want` solo y el reparador sigue después. Así
     sale hoy el `super(limit)` de `Unset` dentro de extract-superclass.
   - Sin superclase, `given superclass(t, u)` no vale, y la intención se rechaza diciendo lo que le falta: "pull-up-field
     needs superclass(a.B, u)" (`intent.unreachable`). El `require` se fue.
   - Lo que rompe un plan se repara de a una regla, en el orden en que están escritas; antes el orden dependía de un
     hash.
   - Quién puede usar qué y el alcance de un nombre todavía son metas de violaciones que ve el verificador, no reglas.
     Por eso, al subir un campo privado, todavía lo abre el reparador después. El ciclo 2 los pasa a reglas, para que
     el plan salga entero.
   - A igual costo decide el orden en que están escritas las definiciones (abrir a `package` va antes que a
     `protected`), como en los quick fixes. Preguntarle a la persona queda para dos caminos que las reglas no
     ordenen, y en estas intenciones no aparece ninguno.

   **Ciclo 2 hecho el 2026-11-02**: quién puede usar qué es una regla, y subir un campo privado lo abre en el mismo
   plan. El reparador ya no interviene: el rastro muestra `set a.A#x visibility = package` dentro de pull-up-field.
   - `rule access`: lo que un uso liga se puede usar desde donde está el uso (JLS 6.6). Un uso hecho sin decir de quién
     liga solo si lo que nombra está en el alcance (`binds`, con `in-scope`); si no, es un problema de alcance y no de
     acceso, y abrir no lo arregla. La meta de `access.insufficient` sigue igual: dice qué hace desaparecer la
     violación que ve el verificador, y la regla dice qué tiene que valer en cualquier estado supuesto.
   - Un estado supuesto es el grafo como quedaría. Antes, lo que `GraphFacts` deriva de dónde está un elemento (su
     tipo, su tipo de primer nivel, su paquete, su archivo, los elementos antes que él, el dueño de un miembro) seguía
     el programa real, y un campo supuesto en otra clase seguía siendo privado de la vieja para las reglas. Ahora lo
     derivado sigue al padre supuesto (`GraphFacts.with`), y lo que entra a un padre va último, como hace `Move`.
   - Un hecho nuevo del grafo: `uses(s, m)`, cualquier referencia de s a m.
   - Las violaciones de antes de un cambio se preguntan una vez por búsqueda, no en cada estado supuesto.

   **Ciclo 3 hecho el 2026-11-02**: dónde está en el alcance un nombre también es una regla (`rule in-scope`, JLS
   6.5.6.1 y 15.12.1). Subir un campo cuyo inicializador lee otro privado sube también ese otro, antes que él, en el
   mismo plan, sin reparar después. Antes, `pull-up-dependency` lo hacía el reparador.
   - La regla se escribe antes que la de acceso, y por eso se repara antes: lo que trae un miembro al alcance puede
     dejarlo también al alcance de la mano (`y` en la misma clase que `x` es privado y alcanza). En el otro orden, el
     plan abría `y` sin necesidad y después lo subía igual.
   - El lugar sale de lo supuesto: lo que entra a una clase va último, así que `before(y, x)` no vale después de
     subir los dos, y el plan lo pide (`Move` antes de `x`).

   **Ciclo 4 hecho el 2026-11-02**: extract-superclass es una intención, y ya no llama a pull-up-field.
   - Su `want` pide: que no haya nada con ese nombre en el paquete del tipo (`given not member(_, p, name, _)`), la
     clase con ese nombre ahí (`member(b, p, name, "class")`, que se crea), entre el tipo y lo que extendía
     (`in-between`), que el tipo la extienda (`superclass(type, b)`, un `Link`) y cada campo dado en ella
     (`each f in fields: parent(f, b)`). `each x in lista:` repite su literal por cada valor de la lista.
   - `in-between(b, t)` está en `java.rules`: b extiende lo que t extendía, como t lo escribe, con sus argumentos de
     tipo, o nada. Lo hace `same-superclass(b, t)`, una relación compuesta como `same-parameters`: solo la toma una
     clase que el plan crea, y la crea con un `Set superclass` del texto que escribió t. `superclass` es una relación
     que cambia un primitivo (`Link extends`), y `subclass` sigue lo supuesto, también para lo que se crea.
   - Los pasos arman sus primitivos al correr, así pueden nombrar algo que el plan creó antes, como la clase nueva.
     Un título que nombra algo creado lo dice por su nombre; si una plantilla `say` no se puede decir, se dice el
     cambio como es, con direcciones. Un camino descartado de una intención se dice con el nombre de la intención, y
     un `_` del rastro se muestra como se escribe.
   - El respaldo (el `want` solo, sin reparar) corre solo un camino al que no le falta nada. Si no hay ninguno, la
     intención se rechaza diciendo qué le falta.
   - El `super(limit)` de `Unset` todavía sale del reparador. La búsqueda dice por qué:
     `X extract-superclass, then open-visibility a.Unset#limit private -> package, then make limit not final: promise
     final of a.Unset#limit: ...`. Subir `limit` rompe `final-where`, y las reglas solo lo reparan sacando el `final`,
     que es una promesa. Unir esa regla con `given-by-its-type` es el ciclo 5.

   **Ciclo 5 hecho el 2026-11-02**: el `super(limit)` de `Unset` sale en el mismo plan de extract-superclass, sin
   reparador.
   - `final-where` dice que un `final` lo da su propio tipo, o lo recibe de un constructor suyo al que el de otro tipo
     se lo pasa primero: `must may-assign(s, f) or given-by-its-type(f, s)`. Una regla rota se cumple haciendo valer
     una de sus alternativas, así que el camino de `given-by-its-type` repara lo que rompe subir `limit`. En un
     estado real esa alternativa nunca vale, porque `passes` solo se hace, así que la regla no deja pasar nada que
     antes no pasara.
   - La meta de `ecj.FinalFieldAssignment` se fue de `knowledge.ref`: el problema ya es `final-where` roto, y los dos
     fixes salen de sus alternativas ("make limit not final" y "create constructor Base(), then pass limit to the
     constructor of Base").
   - El rastro dice el plan entero: `extract-superclass, then open-visibility a.Unset#limit private -> package, then
     create constructor AbstractUnset(), then pass limit to the constructor of AbstractUnset`. Es el ejemplo del
     apéndice, deducido.
   - Un paso de creación da por nombre dónde se crea (`${in}`) y en qué tipo (`${type}`), para decir lo que se crea
     dentro de algo que también se crea. Las plantillas ya no llaman a `name(...)` sobre lo que todavía no existe.

   **Costo de las intenciones, medido el 2026-11-02** con `PerformanceProbe`, que ahora también sube un campo, en el
   sample de 100 clases (unos 10.000 usos):
   - pull-up-field tarda 1,2 s, contra 0,35 s del script que era; los dos incluyen la verificación. Antes de un ajuste
     tardaba 6 s: `GraphFacts` recorría todos los elementos para `parent(x, u)` con solo `u`, y para `alike` con los dos
     conocidos, y la regla de alcance los pregunta por cada uso. Ahora lo que hay en un padre se le pregunta al padre
     (también para `member`, `constructor` y `parameter` con su dueño).
   - De lo que queda, unos 430 ms son las reglas de alcance y de acceso, preguntadas enteras dos veces: antes del
     cambio y en el estado supuesto.
   - Los quick fixes de una línea ya tardaban 2,4 s, y con esas dos reglas tardan 2,9 s.
   - Lo que sigue para bajarlo: preguntar las violaciones solo alrededor de lo que cambió, y compartir la evaluación de
     antes entre las búsquedas de un mismo problema.
5. *Refactorings nuevos sin Java.* Extract Variable, Introduce Parameter, Introduce Field desde un local.

**Cómo se sabe si funcionó.**

- `knowledge.ref` no tiene efectos, y el catálogo no tiene pasos: cada refactoring es una intención.
- El `super(limit)` sale de las reglas de final, alcance y construcción, sin nada escrito para ese caso. *Desde el
  ciclo 5 de la segunda parte de la fase 4, en el mismo plan que extract-superclass.*
- La fase 4 baja la cantidad de funciones de `ScriptFunctions`.
- Las fases 4 y 5 no tocan ningún archivo de los drivers.
- Ahora sí se aceptan `this.limit = limit * 2` y cualquier otra sentencia que no se pueda decir: se mueve entera,
  opaca, en vez de rechazarse.

**Preguntas abiertas para decidir antes de la fase 1.** Las tres primeras se decidieron el 2026-11-01, al empezar la
fase 2, con lo propuesto.

1. La identidad de una sentencia después de una edición a mano: ¿orden y texto (propuesto) o contenido normalizado?
   *Decidida: orden y texto.* Lo que los dos cuerpos dicen en el mismo orden, lo más que se pueda, conserva sus ids
   (`Reconciliation.statements`); una sentencia cuyo texto cambió es nueva.
2. El `site` de una referencia: ¿la sentencia (propuesto), o el miembro más un campo nuevo con la sentencia? Lo
   segundo es menos invasivo, pero deja dos maneras de decir dónde está un uso. *Decidida primero la sentencia, y
   cambiada en el ciclo 2 de la fase 2, antes de escribirla: el miembro sigue siendo el `site`, y cada uso dice en qué
   sentencia de un cuerpo está (`Reference.statement`).* Al contarlos, todo lo que lee `site` quiere decir "el miembro
   que hace el uso": el verificador, las reglas de estáticos, la navegación, los cuerpos dichos que declaran sus usos
   sobre el método. Con la sentencia como `site`, cada uno tendría que subir hasta el miembro (`Program.memberOf`), y
   olvidarlo es un error que no se ve; `inside` ya lo mostró en el ciclo 1. La proyección en memoria tendría que
   rehacer todos sus usos. Y la información es la misma: con el campo, el borrado o la mudanza de una sentencia saben
   sus usos (`referencesIn`), y las reglas pueden preguntar por ellos. Las dos maneras de decir dónde está un uso
   existen igual: el `site` y lo que el `site` es.
3. Los bloques anidados (`if`, `for`, `try`): ¿sentencias hijas de su sentencia desde el principio, o opacos hasta
   que un refactoring los pida (propuesto)? *Decidida: opacos*; una sentencia es una del primer nivel del cuerpo.
4. Las expresiones siguen opacas (propuesto). Extract Variable desde una expresión usa la selección de la persona,
   que el driver convierte en un local y una sentencia.
5. Qué motor evalúa las reglas: resuelta en la decisión 33. Uno propio y sencillo, con la semántica de Datalog,
   como producto aparte (`rules`), detrás de un contrato que un motor maduro podría cumplir con un adaptador.

#### Apéndice de la etapa 3: cómo quedaría el conocimiento

Acá va solo lo que cambia con el tiempo: el conocimiento, que es lo que se agrega y se ajusta para que el sistema
haga más refactorings y quick fixes. En ningún archivo hay un quick fix escrito ni un paso de un refactoring; hoy, en
cambio, son 25 efectos escritos a mano. La sintaxis es un boceto: semántica de Datalog con la forma de los `.ref`.

Lo fijo, que no se muestra porque no cambia:
- el grafo, con los hechos que el driver lee;
- los primitivos y qué hechos cambia cada uno;
- tres invariantes de sentido, sin lenguaje: cada uso liga lo mismo, lo que corría corre donde corría, y un valor
  llega a otro método por un parámetro;
- el planificador;
- el verificador, con el compilador como juez.

**`java/language.rules`: cómo es Java.**

```
# How every Java program is. Nothing here is about a refactoring or a quick fix.
# Read from the graph: in(s) the method or constructor of a statement, owner(x) what declares x, top,
# package, uses/reads/writes/calls(s, x), first(k), callee(s), arguments(s, c), params(c),
# constructors(t), superclass(t), inherited(t), overrides(m, n), subclass(a, b), and the facts.

# ---- names
rule own-names "a parameter or a local is used only inside its own method or constructor"
  for s, x where uses(s, x) and (param(x) or local(x))
  must in(s) = owner(x)
end

# ---- access
order visibility: private < package < protected < public
def accessible(s, m) when visibility(m) = public
def accessible(s, m) when visibility(m) = package   and package(s) = package(m)
def accessible(s, m) when visibility(m) = protected and (package(s) = package(m) or subclass(top(s), owner(m)))
def accessible(s, m) when visibility(m) = private   and top(s) = top(m)

rule access "a member is used only where it is accessible"
  for s, m where uses(s, m)
  must accessible(s, m)
end

# ---- static
rule no-object "a static method uses an instance member of its type only through an object"
  for s, m where uses-own(s, m) and not static(m)          # uses-own: written as m or this.m
  must not static(in(s))
end

# ---- construction
# A class that declares no constructor has one, with no parameters.
def constructors(t) = [implicit constructor] when class(t) and declared-constructors(t) = []
# A constructor starts with super(...) or this(...); when neither is written, with super(), unwritten.
def starts-with-this(k) when callee(first(k)) in constructors(owner(k))
def implicit-super(k)   when constructor(k) and not constructor(callee(first(k)))

rule construction "an unwritten super() needs a constructor without parameters in the superclass"
  for k where implicit-super(k)
  must some c in constructors(superclass(owner(k))): params(c) = [] and accessible(k, c)
end

# ---- final fields
rule final-once "a final field gets its value in its declaration, or in each constructor of its type"
  for f where final(f) and not initialized(f)
  must every k in constructors(owner(f)) where not starts-with-this(k): assigns(k, f)
end

rule final-where "only a constructor of its own type assigns a final field"
  for s, f where writes(s, f) and final(f)
  must in(s) in constructors(owner(f))
end

# ---- calls
rule arity "a call gives one argument to each parameter"
  for s, c where calls(s, c)
  must count(arguments(s, c)) = count(params(c))
end

rule one-result "a method gives back one value at most"
  for m where method(m)
  must count(results(m)) <= 1
end

# ---- abstract methods and overriding
rule complete "a class that is not abstract has a body for each method it inherits without one"
  for t, m where class(t) and not abstract(t) and m in inherited(t) and abstract(m)
  must some n in members(t) + inherited(t): overrides(n, m) and not abstract(n)
end

rule override-access "an override is at least as accessible as what it overrides"
  for m, n where overrides(m, n)
  must visibility(m) >= visibility(n)
end
```

**Promesas, valores y lo que dice el compilador.**

```
# java/promises.rules: what a declaration promises. Changing it may leave everything compiling and
# still change what the code means for somebody: only people choose that, no repair does it alone.
promise final of fields     "the value never changes once the object is made"
promise static of fields    "one value for all objects, or one for each"
promise signature of public "code outside the workspace may call it"

# java/values.rules: how Java writes the value a type starts with.
value start(t) = "0"     when t in byte, short, int, long, float, double
value start(t) = "false" when t = boolean
value start(t) = "null"  otherwise

# java/compiler.rules: what Eclipse's compiler says, as the rule that does not hold there.
problem ecj.UninitializedBlankFinalField       is final-once   for the field it names
problem ecj.FinalFieldAssignment               is final-where  for the statement it marks
problem ecj.StaticMethodRequested              is no-object    for the statement it marks
problem ecj.NonStaticFieldFromStaticInvocation is no-object    for the statement it marks
problem ecj.NotVisibleField                    is access       for the statement it marks
problem ecj.AbstractMethodMustBeImplemented    is complete     for the class it marks
problem ecj.MethodReducesVisibility            is override-access for the method it marks
problem ecj.UndefinedConstructorInImplicitConstructorCall is construction for the constructor it marks
```

**`preferences.rules`: el orden de las propuestas.**

```
# Among the ways that work, which comes first. It changes the order, never what is correct.
cost every change 1
cost a change that touches a public member +3
cost each step a visibility widens +1
prefer what is created as hidden as it can be
```

**`refactorings.rules`: lo que la gente pide.**

```
# What people ask for: what has to be true afterwards. How to get there, and what else has to change
# for the code to still be Java and still do the same, is deduced.

refactoring pull-up-field "Moves a field to the superclass of its type"
  param field : FIELD
  want owner(field) = superclass(owner(field))
end

refactoring extract-superclass "A class between a type and its superclass, with the fields given"
  param type   : CLASS
  param name   : text default "Abstract${name(type)}"
  param fields : FIELD* of type
  new CLASS base named name in package(type)
  want superclass(base) = superclass(type)
  want superclass(type) = base
  want owner(f) = base for each f in fields
end

refactoring extract-method "Statements become a method, called where they were"
  param statements : STATEMENT* in a row
  param name       : text default "extracted"
  new METHOD m named name in owner(in(first(statements)))
  want in(s) = m for each s in statements
end

refactoring make-static "A method that needs no object"
  param method : METHOD
  want static(method)
end

refactoring inline-method "Each call does what the method does, and the method goes"
  param method : METHOD
  want gone(method)
end

refactoring safe-delete "Deletes what nothing uses"
  param element : any
  want gone(element) alone          # alone: nothing else may change to get there
end
```

Inline Method y Safe Delete difieren en una palabra: cuánta libertad tiene el planificador para lograr lo pedido.

**Lo que el sistema deduce con eso.** Extract Superclass sobre una clase cuyo constructor inicializa un `final`:

```
extract-superclass Unset → AbstractUnset, [limit]
  AbstractUnset is created, Unset extends it, limit moves to it                     asked
  ✗ access       twice() reads limit, private in another class
                 → limit package                                                  narrowest that works
  ✗ final-where  "this.limit = limit" assigns limit in Unset(int)
    a. move that statement into a constructor of AbstractUnset
       · AbstractUnset has only the implicit one → write it: AbstractUnset()       same meaning
       ✗ own-names     the statement reads limit, a parameter of Unset(int)
                       → AbstractUnset() takes a parameter limit; the statement reads that one
       ✗ construction  Unset(int) starts with an unwritten super(); none without parameters is left
                       → Unset(int) starts with super(v); v is what the statement read there: limit
       ✓ keeps meaning, touches no promise                                         applied
    b. limit not final               ✓ compiles  ✗ promise "final"                 offered, not applied
    c. limit = … in its declaration  the value exists only inside Unset(int)        discarded
    d. limit back to Unset           undoes what was asked                          discarded
```

Las mismas reglas, sin refactoring de por medio, frente a `ecj.UninitializedBlankFinalField` en una clase sin
constructores, dan los tres fixes que hoy están escritos a mano:
- un valor en la declaración: pregunta cuál y propone `start(int)`;
- un constructor que recibe el valor: los `new Q()` necesitan un argumento, así que pregunta y propone `0`;
- sacar el `final`: rompe una promesa, así que solo si la persona lo elige.

Extract Method, con los parámetros y el resultado deducidos:

```java
int average(List<Item> items) {
    int sum = 0;                                   // s1
    for (Item i : items) sum += i.price();         // s2
    int count = items.size();                      // s3
    return sum / count;                            // s4
}
```

```
extract-method [s2] addPrices
  addPrices is created; s2 moves into it                                           asked
  s2 still has to run where it ran     → a call to addPrices where s2 was          what runs, runs
  ✗ own-names  s2 reads items and sum, which belong to average
     items: only read                  → parameter items; the call passes items
     sum:   read, written, read after  → parameter sum, and it comes back: sum = addPrices(items, sum);
  → private int addPrices(List<Item> items, int sum) { for (Item i : items) sum += i.price(); return sum; }

extract-method [s2, s3] addPrices
  sum and count are written, and s4 reads both after: two values would come back
  ✗ one-result  no way gives back two → refused: "sum and count would both come back"
```

Ese rechazo es el mensaje de IntelliJ ("multiple output values"), y nadie lo programó: sale de que en Java un método
devuelve un solo valor.

Un quick fix de estáticos, con una cadena que se corta:

```java
class Prices {
    double rate = 0.21;
    double gross(double net) { return net * factor(); }
    double factor()          { return 1 + rate; }
    static double check(double net) { return gross(net); }    // ecj.StaticMethodRequested
}
```

```
✗ no-object  check() is static and calls gross() without an object
  a. gross() static
     ✗ no-object  gross() calls factor() without an object → factor() static
        ✗ no-object  factor() reads rate without an object → rate static
           ✗ promise "static of fields": one rate for all Prices                  this way stops
  b. check() not static                  1 change; nothing static calls it          first
  c. check() calls gross() on an object  a parameter Prices p; its callers
                                         have to pass one                           asks which
```

Si `factor()` no leyera `rate`, el camino (a) llegaría hasta el final: "make gross() and factor() static". El
`make-static` escrito hoy hace uno solo y deja el error en el siguiente.

**Cómo crece: un archivo nuevo.** Un día se agrega esto, y nada más:

```
# java/exceptions.rules
def checked(e) when subclass(e, java.lang.Exception) and not subclass(e, java.lang.RuntimeException)

rule handled "a checked exception a call may throw is caught around it or declared by its method"
  for s, c, e where calls(s, c) and e in throws(c) and checked(e)
  must caught(s, e) or some d in throws(in(s)): subclass(e, d)
end

rule override-throws "an override throws no checked exception that what it overrides does not"
  for m, n, e where overrides(m, n) and e in throws(m) and checked(e)
  must some d in throws(n): subclass(e, d)
end

problem ecj.UnhandledException                  is handled         for the statement it marks
problem ecj.IncompatibleExceptionInThrowsClause is override-throws for the method it marks
```

Sin tocar ningún refactoring ni otra regla, aparece:
- un quick fix nuevo: sobre `Files.readString(path)` en `load()`, "add throws IOException to load()"; si `start()`
  llama a `load()`, eso rompe `handled` ahí, y el planificador sigue la cadena: "…and to start()";
- Extract Method con excepciones: el método nuevo declara `throws IOException` cuando lo que se extrae lo tira;
- Extract Interface y Pull Up Method con excepciones: la declaración de arriba también lo declara, por
  `override-throws`.

La regla necesita un hecho que hoy el grafo no tiene: qué declara tirar cada método del workspace (de los métodos de
biblioteca lo dice el compilador). Es el único caso en que cambia código fijo: el driver aprende a leer un hecho
nuevo. "Rodear con try/catch" recién aparece cuando los bloques estén en el grafo, porque `caught` habla de ellos.

**Lo que se iría modificando.**

| Archivo | Qué contiene | Qué se gana cuando crece |
|---|---|---|
| `java/language.rules` y otros `java/*.rules` | una regla por cada cosa que Java exige | más problemas detectados, más fixes, y refactorings que ya existían andando en más casos |
| `java/compiler.rules` | qué regla rota es cada error de ECJ (hay cientos) | más errores del compilador con fixes |
| `java/promises.rules`, `java/values.rules` | qué cambia el sentido; cómo se escriben los valores | qué se aplica solo y qué se pregunta |
| `preferences.rules` | costos | el orden de las propuestas |
| `refactorings.rules` | intenciones, unas líneas de `want` cada una | refactorings nuevos, que sirven para cualquier lenguaje |
| hechos del driver (rara vez) | el vocabulario del grafo | lo que las reglas pueden decir |

`refactorings.rules` no tiene nada de Java: para otro lenguaje harían falta sus propios `*.rules` y un driver, y las
intenciones se reusan.

## 10. Glosario

- **Elemento**: algo con identidad en el programa: paquete, tipo, campo, constructor, método, parámetro.
- **Referencia**: un uso o un vínculo de un elemento (site) a otro (target), con un modo.
- **Vínculo**: una referencia de declaración: extends, implements, declared.
- **Uso sin resolver**: un uso cuyo nombre no liga nada; un retarget lo liga.
- **Tipo de biblioteca**: un tipo de fuera del workspace que el programa nombra; nada lo cambia.
- **Hecho**: un valor tipado sobre un elemento: visibilidad, estático, abstracto, final.
- **Dirección**: cómo se nombra un elemento desde el texto; cambia con rename y move. El id no.
- **Identidad**: la tabla dirección → id; la transfieren los primitivos, y la reconciliación después de una edición
  a mano.
- **Reconciliación**: emparejar lo que una edición a mano cambió de dirección con lo que era, sin adivinar.
- **Primitivo**: un pedido de cambio abstracto, record de `Primitive`.
- **Cuerpo**: lo que hace un método, dicho sin lenguaje sobre elementos (`Body`); cada proyección lo escribe en el
  suyo, y el motor verifica que lo escrito liga lo que el cuerpo nombró.
- **Fusión**: borrar un miembro dentro de otro (`Delete` con `into`); sus usos pasan al otro y los dos son uno.
- **Necesidad**: lo que un efecto del conocimiento requiere antes (`needs`); vale por un `fact` o lo hace otro efecto,
  deducido hacia atrás.
- **Change**: lo que un primitivo devuelve: producido, expectativas, pins.
- **Expectativa**: una diferencia entre antes y después que el cambio hizo a propósito; el verificador la espera.
- **Pin**: un aspecto que el refactoring fijó a propósito; una reparación no puede moverlo.
- **Proyección**: el driver de un lenguaje: lee el programa y ejecuta primitivos como ediciones.
- **Transacción**: un cambio verificado: línea base, primitivos, verificación, reparación, cierre o deshacer.
- **Vista**: una comparación entre antes y después que produce violaciones de un tipo.
- **Violación**: un invariante roto, con código estable.
- **Plan**: una propuesta de cambio para una violación o un diagnóstico; el verificador juzga si sirvió.
- **Rastro**: el árbol de lo que pasó en una transacción.
- **Garantía**: `guaranteed` si nada quedó asumido; `assumed` si hay obligaciones.
- **Refactoring**: una composición de primitivos y otros refactorings, con parámetros declarados.
- **Run**: lo que un refactoring ve mientras corre, dentro de una transacción o directo sobre una proyección.
- **Valor**: lo que los refactorings se pasan y los scripts calculan: un elemento o una referencia por id, texto, un
  valor de verdad o una lista.
- **Capacidad**: esquema más invocación; lo único que los adaptadores conocen.
- **Adaptador**: la CLI, la UI o el servidor MCP: traducen palabras, teclas o JSON-RPC a capacidades, y sus respuestas
  a texto o pantalla.
- **Test de producto**: usa un producto solo, en su vocabulario, con dobles de lo que necesita de afuera.
- **Test de contrato**: verifica que cada implementación de una interfaz cumple lo que sus usuarios asumen.
- **Test de aceptación**: un escenario de usuario que atraviesa todos los productos.
