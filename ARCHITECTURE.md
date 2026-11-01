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
                 acceptance            (solo tests: donde los productos se encuentran)
                     |
                  session              (ensambla; después, los adaptadores encima)
          ___________|____________
         |           |            |
         |       knowledge        |
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
| knowledge   | engine, refactoring                                        | planificador por metas                       |
| session     | java, engine, refactoring, knowledge; JavaParser en runtime | el ensamblado                                |
| acceptance  | session, solo para tests                                   | escenarios transversales, samples, reglas    |

Cómo se hace cumplir:

- Un producto compila contra lo que declara y lo que está debajo de eso; nada de arriba ni de al lado.
- `refactoring` declara `engine` desde la fase 5: un run corre dentro de una transacción.
- Solo `java` compila contra JavaParser: `session` lo recibe en runtime, para ejecutar el driver.
- javac viene con el JDK, así que "solo `java` usa la API del compilador" lo verifica ArchUnit, igual que "solo
  `knowledge` implementa remedios". Son las dos reglas que Maven no puede expresar, y viven en `acceptance`.
- `acceptance` no es un producto: es donde los productos se encuentran.
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
  `EventLog` (JSONL append-only), `TextDiff`. Sin ningún dominio del IDE.
- **Prueba de autonomía.** Ya la pasa: se usa con lambdas en un test.
- **Estado actual.** Se mantiene como está.

### 5.2 `program`: modelo de programas para herramientas

- **Qué es.** Una librería para representar un programa orientado a objetos como un grafo con identidad estable,
  consultarlo, comparar dos estados y describir cambios sobre él. No sabe de ningún lenguaje ni de texto.
- **Para qué lo usarías.** Un linter de dependencias, métricas, un visualizador de jerarquías, otra herramienta de
  refactoring sobre otro lenguaje.
- **Dominio.**
  - `Element`: `id`, `kind`, `name`, `parent`, `facts`. `Kind`: PACKAGE, CLASS, INTERFACE, ENUM, FIELD,
    CONSTRUCTOR, METHOD, PARAM.
  - `Reference`: `site`, `target`, `mode`, `name` (cómo se escribió). Modos de uso: READ, WRITE, UPDATE, CALL, NEW,
    TYPE. Modos de vínculo: EXTENDS, IMPLEMENTS, DECLARED (el tipo declarado de un campo, parámetro o retorno). Un
    vínculo es una referencia porque la jerarquía y el verificador lo tratan igual que a un uso: hoy son strings
    duplicados en atributos y eso obliga a re-resolver nombres en todos lados.
  - `Unresolved`: un uso cuyo nombre no liga nada (site, nombre, modo). Su id está en el mismo espacio que el de
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
    miembro y nombre. Se renderiza como `a.B#m(int)/x` para humanos y tests. Consecuencia buscada: un miembro no
    cambia de dirección cuando su dueño o un tipo de parámetro se renombra o se mueve.
  - `Identity`: tabla dirección → id. `of(address)` crea si no existe; `rebind(type, newType)` para rename y move de
    tipos; `forget`. Con instantánea. Es la única operación de identidad: el parser nunca decide quién es quién.
  - `Program`: el grafo inmutable, con índices por padre, por destino y por dirección. Consultas: children,
    members, referencesTo, referencesFrom, supertypes, superclassChain, isSubclass, overriddenAll, family,
    enclosingType, topLevel, byAddress, signature.
  - `Diff`: `Program.compare(before, after)`: referencias perdidas y ganadas (por site y target), elementos nacidos e
    idos, hechos cambiados. Es lo que consumen las vistas del motor.
  - Vocabulario de cambio. `Primitive`, un tipo sellado de records:
    - `Create(kind, parent, name, spec)`: `spec` trae hechos textuales iniciales (type, default para parámetros,
      body), así la creación es un paso y no dos con marcadores.
    - `Delete(element)`.
    - `Move(element, parent, before)`: también reordena parámetros.
    - `Rename(element, name)`.
    - `Set(element, fact, value)`: hechos cerrados y hechos textuales (type textual, body, initializer).
    - `Link(element, mode, target)`: extends, implements, declared hacia un tipo del workspace. Reemplaza
      `set superclass`, `set implements` y `set type` con tipo del workspace.
    - `Retarget(reference, target)`.
    - `Extract(method, fragment, name)` e `Inline(reference)`: el fragmento es una dirección opaca que entiende la
      proyección (hoy `primera-última` en líneas).
  - `Change`: lo que un primitivo devuelve. `produced` (el elemento creado, si hay), `expectations` y `pins`.
    `Expectation`: `Retargeted(site, from, to)`, `Resited(target, from, to)`, `Dropped(site, target)`,
    `UsesGone(element)`. `Pin(element, aspect)` con aspecto parent, name o visibility: lo que el refactoring hizo a
    propósito y una reparación no puede deshacer.
- **Qué ignora.** Texto, posiciones, archivos, parser, compilador, cuerpos de métodos.
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
  padre mientras llegaba otro. `Identity.transfer` le da al id viejo la dirección nueva.
- **Abierto.** Claves de tipo con genéricos anidados (hoy el texto descrito con los ids sustituidos); constantes de
  enum (no se modelan); variables locales como elementos (no, hasta que un refactoring lo pida).

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
  - `LanguageRules rules()`: accesibilidad y visibilidades posibles de un elemento. Ya no ofrece candidatos de
    import: el programa tiene los tipos que un uso podría nombrar.
- **Obligaciones del driver**, que el kit de conformidad (un test abstracto que corre contra cada implementación)
  verifica:
  1. Identidad: un elemento conserva su id a través de rename, move, set y link; lo creado recibe un id nuevo; lo
     borrado desaparece y no vuelve.
  2. Cada primitivo declara exactamente lo que cambió a propósito: su `Change` es la única fuente de expectativas.
  3. Lectura reproducible: `program()` después de un `apply` es igual al de una proyección nueva sobre el mismo
     texto, salvo los ids.
  4. `snapshot` y `restore` devuelven texto, identidad y grafo idénticos.
  5. Un primitivo rechazado no deja ningún cambio.
  6. Un elemento de biblioteca nunca cambia: todo primitivo que lo cambiaría se rechaza (`projection.library`, la
     regla vive en `PrimitiveRejected.unlessInWorkspace` y la usan los dos drivers).
  7. Un retarget de un uso sin resolver lo liga: desde ahí es una referencia.
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
  fixture nombra `String` (de la biblioteca) y `Money` (que no existe), y suma las obligaciones 6 y 7.
- **Abierto.** Si `diagnostics` y `completion` quedan en el mismo contrato o en uno de análisis aparte: juntos
  hasta que estorbe.

### 5.4 `java`: editor estructural de Java

- **Qué es.** Una librería que, dado un conjunto de fuentes Java en memoria, lee el programa como grafo y ejecuta
  primitivos como ediciones mínimas de texto, conservando formato y comentarios. La única que conoce JavaParser y
  javac.
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
     elemento), `Resolution`, `Keys`, `WorkspaceTypeSolver`, `JavacCheck`, `Bodies` (flujo de datos y precedencia
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
  `projection.unparsable`, en vez de romper. `set` todavía tiene un caso que solo entiende Java: `initializer`. Desde
  la etapa 2 el Reader suma los tipos de biblioteca que el código nombra (con id `lib:` y su nombre, fuera de la
  tabla de identidad, donde cambiarían las claves de los miembros) y los tipos escritos que no ligan nada, con los
  tipos del JDK que podrían nombrar. Ligar uno escribe el nombre del destino y lo importa si hace falta. Reemplazar
  un tipo vinculado declara que los usos dentro de él, como sus argumentos de tipo, se van con él. Una edición a mano
  reconcilia la identidad por rondas (tipos, lo que contienen, parámetros), leyendo de nuevo entre una y otra; lo
  que la edición quitó se olvida, para que su id no vuelva con otra cosa.
- **Abierto.** Invalidación selectiva: se reparsea solo lo tocado, pero el grafo se relee entero. Borrado de tipos
  anidados. Un miembro llevado a mano de un tipo a otro, o de un archivo a otro, no se reconoce: cada edición es de
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
    Access, Dispatch (overrides perdidos o nuevos), Compiler (solo si el código compilaba antes; corre última porque
    es la más cara). Futuras: Projection (direcciones duplicadas), Init.
  - `Verifier`: una lista de vistas más la exención de lo que ya estaba roto antes de la transacción.
  - `Violation`: código estable `vista.tipo`, site, target, hechos para los remedios.
  - `Remedy` y `QuickFix`: proponen `Plan`s para una violación o un diagnóstico, sobre la proyección, que trae sus
    reglas. `Plan`: título, preguntas (asks), acción sobre la proyección.
  - `Repairer`: toma la primera violación, prueba planes sin preguntas del más barato al más caro desde un punto
    guardado; conserva el que hace desaparecer esa violación sin mover un pin; topes de 10 rondas y 16 ramas.
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
  dirección (`a.P#x -> a.P#count`).

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
    variable, función, filtro) y `ScriptFunctions` (consultas sobre el programa y las reglas). Quien embebe scripts,
    como los efectos de `knowledge`, le pregunta al lenguaje dónde termina un bloque (`ScriptParser.blockLength`).
  - El catálogo `standard.ref`, con presupuesto de 20 sentencias por script y sus fugas marcadas (`CatalogTest`).
  - `ChangeSignature`: el único compuesto escrito en Java, porque su parámetro es una lista de registros.
- **Qué ignora.** Lenguajes y sesión. De la verificación sabe solo que existe: la pide a la transacción en la que
  corre.
- **Prueba de autonomía.** `RunTest`, sobre `MemoryProjection`: encapsulate-field corre sin motor; dentro de una
  transacción su expansión da la secuencia esperada; y `try` y `oblige` se comportan como se espera en cada lugar.
- **Estado actual** (fase 5 cerrada, 2026-10-31). `ide.refactoring` (`Refactoring`, `Library`, `Run`, `Args`,
  `Value`, `Primitives`, `ChangeSignature`) e `ide.refactoring.script`. Los primitivos se leen del vocabulario:
  nada se registra a mano. Fugas de Java, marcadas donde están: las plantillas de cuerpo de generate-getter,
  generate-setter e introduce-factory en el catálogo, e `initializer` en el conocimiento. `import` dejó de ser una
  fuga en la etapa 2: es un retarget (funciones `unresolved-in` y `types-named`).
- **Abierto.** Un lenguaje neutral de cuerpos para las plantillas (`return field`, `assign field param`,
  `return new owner(params)`) que la proyección renderice (decisión 12, etapa 2).

### 5.7 `knowledge`: planificador por metas

- **Qué es.** Dada una base de metas (qué debe valer para que un problema desaparezca) y efectos (qué hace valer un
  predicado, bajo qué condición, con qué costo, con qué preguntas), propone planes ordenados por costo con una traza
  explicable. Podría planificar otra cosa que código.
- **Dominio.** `Goal(code, predicate)`, `Effect(name, title, makes, when, for, cost, asks, do)`, `Predicate`,
  `Search` con su traza, `Plan`. Implementa `Remedy` y `QuickFix` del motor y ejecuta acciones por el `Run` de
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
  import liga el uso sin resolver al que apunta el diagnóstico con un tipo de ese nombre: un retarget, sin Java.
- **Abierto.** Encadenamiento hacia atrás en varios niveles (la spec pedía 8 niveles, 4 pasos y 12 planes; hoy hay
  un nivel); `fact` e `invariant` del lenguaje de conocimiento.

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
- **Abierto.** Las operaciones que todavía no son capacidades (edit, save, asks, model) entran cuando un adaptador
  las pida. `Capabilities` lista las operaciones a mano: si un día se desfasan de la fachada, se derivan de ella.

### 5.9 `adapters`: cli, ui, mcp

- **Qué es.** Traductores: argumentos a capacidades, respuestas JSON a texto o pantalla. CLI primero, después UI
  Swing con keymap de Eclipse, después MCP.
- **Regla propia.** Sin lógica y sin dependencias más allá de `session` y `core`. Si un adaptador necesita un `if`
  sobre el dominio, falta una capacidad.
- **Estado actual.** No existen, por decisión: llegan al final.

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

| Módulo           | Al empezar la fase 2 | Al cerrar la fase 2 | Al cerrar la fase 3 | Al cerrar la fase 4 | Al cerrar la fase 5 | Al cerrar la fase 6 | Etapa 2, ítems 1 y 2 |
|------------------|----------------------|---------------------|---------------------|---------------------|---------------------|---------------------|----------------------|
| program          | 8                    | 12                  | 12                  | 12                  | 12                  | 13                  | 19                   |
| projection-kit   | (no existía)         | 8                   | 8                   | 8                   | 8                   | 8                   | 10                   |
| java             | 0                    | 8                   | 24                  | 24                  | 24                  | 24                  | 29                   |
| engine           | 0                    | 9                   | 9                   | 17                  | 19                  | 19                  | 19                   |
| refactoring      | 1                    | 3                   | 3                   | 3                   | 11                  | 11                  | 11                   |
| knowledge        | 0                    | 0                   | 0                   | 0                   | 3                   | 5                   | 6                    |
| core, projection | 0                    | 0                   | 0                   | 0                   | 0                   | 0                   | 0                    |
| session          | 13                   | 13                  | 13                  | 13                  | 13                  | 22                  | 22                   |
| acceptance       | 102                  | 103                 | 104                 | 104                 | 104                 | 104                 | 107                  |

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
| 12 | Cuerpos en plantillas    | lenguaje neutral de cuerpos renderizado por la proyección                                  | diferida, etapa 2 |
| 13 | Módulos Maven            | un módulo por producto, más `acceptance` para los escenarios transversales                  | tomada, 2026-10-31 (5d641a2) |
| 14 | Tests                    | producto, contrato y aceptación, en doble ciclo (sección 6)                                | tomada, 2026-10-31 |
| 15 | Ejecución                | un único `Run`, dentro de una transacción o directo sobre una proyección; reemplaza a `Ctx` | tomada, 2026-10-31 (6911992) |
| 16 | Valores de scripts       | `Value` tipado (`Elem`, `Ref`, `Text`, `Bool`, `Items`); id o dirección se resuelve en `Args` | tomada, 2026-10-31 (fb7e3e9) |
| 17 | Transacción y reparador  | la transacción nace con su reparador; `attempt(label, body)`, `commit()`, `Run.in(tx, library)` | tomada, 2026-10-31 (85bb676) |
| 18 | Tipos de biblioteca      | los que el workspace nombra son elementos `LIBRARY`, con su nombre como identidad; nada los cambia y la jerarquía es la del workspace | tomada, 2026-10-31 (a5fdf89) |

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
| `set` como switch de operaciones (marcadores, `default` y `order` resueltos en 8f0ed6a; `import` en 0a738bf; queda `initializer`) | `ide.java.primitives.SetAttribute` | etapa 2 |
| `MemoryProjection` en un solo archivo grande (resuelto en 9c507a0)                  | `projection-kit`       | 3    |
| Búsqueda de declaración, unidad y ruta repetida por primitivo (resuelto en 646c9bc) | `ide.java`             | 3    |
| Vistas no enchufables; vista del compilador dentro del reparador (resuelto en 6bbf600) | `ide.engine`       | 4    |
| Rastro como strings con sigilos (resuelto en 34a3b0e)                              | `ide.engine`           | 4    |
| Runtime de refactorings como clase anónima de la sesión; `Ctx` falso en knowledge (resuelto en 6911992) | `ide.session`, `ide.knowledge` | 5 |
| Valores de scripts como strings; id o dirección resuelto en cada función (resuelto en fb7e3e9) | `ide.refactoring.script` | 5 |
| Plantillas de cuerpo en Java dentro del catálogo e `initializer` en el conocimiento (marcadas donde están en fb7e3e9; `CatalogTest` las cuida; `set import` se fue en 0a738bf) | `standard.ref`, `knowledge.ref` | etapa 2 |
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

   Abierto: los campos y métodos que no ligan nada todavía no son usos del programa (el Reader no distingue sin
   riesgo un nombre sin resolver de una variable local). Cuando lo sean, el fix de create-field partirá del uso y no
   del nombre en mayúscula del diagnóstico, y la completion y los fixes compartirán una sola pregunta: qué puede
   ligar este uso desde su sitio.
2. Reconciliación estructural de identidad en ediciones manuales (hoy un rename a mano equivale a borrar y crear).
   Hecho en dos ciclos:
   - afd56af: lo que una edición a mano renombra, cambia de firma o mueve de paquete conserva su id, y lo que quita
     no vuelve. Antes un elemento borrado a mano volvía con su id viejo si se escribía de nuevo su nombre.
   - 7e7a8fd: el paso de la edición dice qué se entendió de ella; un tipo renombrado es una línea, no una por miembro.

   Abierto: con la identidad estable, una edición a mano se puede verificar contra lo que dejó roto en otros
   archivos y ofrecer seguir el rename. No se hizo todavía: verificar también el archivo editado marcaría como
   violaciones los cambios hechos a propósito, y ofrecer seguir el rename necesita los usos de miembros sin resolver
   (lo abierto del ítem 1). Tampoco se reconoce un miembro llevado de un tipo o de un archivo a otro.
3. Invalidación selectiva del grafo.
4. Lenguaje neutral de cuerpos para plantillas; se van las fugas de Java del catálogo.
5. Lo que falta del catálogo: pull-up-unifying, borrado de tipos anidados, encadenamiento en varios niveles del
   conocimiento.
6. Adaptadores: CLI, luego UI, luego MCP, cada uno como módulo que declara solo `session` y `core`.

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
- **Test de producto**: usa un producto solo, en su vocabulario, con dobles de lo que necesita de afuera.
- **Test de contrato**: verifica que cada implementación de una interfaz cumple lo que sus usuarios asumen.
- **Test de aceptación**: un escenario de usuario que atraviesa todos los productos.
