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
var tx = engine.open(projection);            // engine
runtime.in(tx).call(refactoring, args);      // refactoring
var result = tx.close(repairer);             // engine
session.record(result);                      // session
```

Si para escribir eso hace falta más, a algún producto le falta un concepto.

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
| refactoring | projection                                                 | refactorings, runtime, lenguaje `.ref`, catálogo |
| knowledge   | engine, refactoring                                        | planificador por metas                       |
| session     | java, engine, refactoring, knowledge; JavaParser en runtime | el ensamblado                                |
| acceptance  | session, solo para tests                                   | escenarios transversales, samples, reglas    |

Cómo se hace cumplir:

- Un producto compila contra lo que declara y lo que está debajo de eso; nada de arriba ni de al lado.
- `refactoring` hoy declara solo `projection`; cuando su runtime necesite la transacción (fase 5) declarará `engine`.
- Solo `java` compila contra JavaParser: `session` lo recibe en runtime, para ejecutar el driver.
- javac viene con el JDK, así que "solo `java` usa la API del compilador" lo verifica ArchUnit, igual que "solo
  `knowledge` implementa remedios". Son las dos reglas que Maven no puede expresar, y viven en `acceptance`.
- `acceptance` no es un producto: es donde los productos se encuentran.
- `projection-kit` es lo que bajaría quien escribe un driver: `java` lo declara para correr el contrato en sus
  tests; `engine` y `refactoring` lo declaran para probarse sobre la proyección en memoria. Siempre solo para tests.

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
  su dirección, y la sobrescritura compara nombre y claves, no texto.
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
  - `LanguageRules rules()`: accesibilidad, visibilidades posibles de un elemento, candidatos de import.
- **Obligaciones del driver**, que el kit de conformidad (un test abstracto que corre contra cada implementación)
  verifica:
  1. Identidad: un elemento conserva su id a través de rename, move, set y link; lo creado recibe un id nuevo; lo
     borrado desaparece y no vuelve.
  2. Cada primitivo declara exactamente lo que cambió a propósito: su `Change` es la única fuente de expectativas.
  3. Lectura reproducible: `program()` después de un `apply` es igual al de una proyección nueva sobre el mismo
     texto, salvo los ids.
  4. `snapshot` y `restore` devuelven texto, identidad y grafo idénticos.
  5. Un primitivo rechazado no deja ningún cambio.
- **El kit, `projection-kit`.** `ProjectionContract` son las obligaciones como tests: cada driver lo extiende y da
  el mismo programa de ejemplo en su lenguaje. `MemoryProjection` es una proyección sin lenguaje sobre un grafo
  mutable: aplica los primitivos al grafo y declara los mismos `Change`. Es la implementación de referencia y lo
  que permite probar `engine`, `refactoring` y `knowledge` solos.
- **Qué ignora.** Refactorings, transacciones, conocimiento, sesión.
- **Estado actual** (fase 2 cerrada, 2026-10-31). `Projection` es `program`, `apply`, `edit`, `snapshot` y `restore`
  tipados, `rules`, `diagnostics` y `completionContext`. El texto (`files`, `sources`) queda en el driver de Java:
  lo usa la sesión, que lo conoce. El contrato verifica las cinco obligaciones más los parámetros (se crean con el
  argumento que pasan las llamadas y se mueven dentro de su método). Corre contra `MemoryProjection` y contra el
  driver de Java, donde encontró un bug: crear un campo con un nombre existente no se rechazaba.
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
  `projection.unparsable`, en vez de romper. `set` todavía tiene dos casos que solo entiende Java: `import` e
  `initializer`.
- **Abierto.** Invalidación selectiva: se reparsea solo lo tocado, pero el grafo se relee entero. Borrado de tipos
  anidados. Reconciliación estructural de identidad en `edit`. Renombrar un parámetro no renombra sus usos en el
  cuerpo, porque esos usos no son referencias del grafo.

### 5.5 `engine`: motor de transacciones verificadas

- **Qué es.** Una librería que abre una transacción sobre una proyección, deja aplicar cambios, compara el estado
  de después con el de antes según vistas enchufables, busca reparaciones entre remedios dados, y cierra con un
  resultado explicable o deshace. No sabe qué es un refactoring, un lenguaje ni una regla concreta.
- **Para qué lo usarías.** Cualquier transformación que deba preservar invariantes y explicar qué pasó: migraciones
  de esquema, cambios de configuración.
- **Dominio.**
  - `Transaction`: la línea base (el `Program` de antes con las expectativas plegadas), los pins, el rastro, el
    checkpoint inicial y marcas (`Mark`) para intentos. Intercepta `apply(Primitive)`: delega a la proyección,
    pliega las expectativas del `Change`, acumula pins, registra el paso. No interpreta primitivos.
  - `View`: `check(baseline, after, rules)` devuelve violaciones. Vistas: Binding (perdida, religada, sin resolver),
    Access, Dispatch (overrides perdidos o nuevos), Compiler (solo si el código compilaba antes; corre última porque
    es la más cara). Futuras: Projection (direcciones duplicadas), Init.
  - `Verifier`: una lista de vistas más la exención de lo que ya estaba roto antes de la transacción.
  - `Violation`: código estable `vista.tipo`, site, target, hechos para los remedios.
  - `Remedy` y `QuickFix`: proponen `Plan`s para una violación o un diagnóstico. `Plan`: título, preguntas (asks),
    acción sobre la proyección.
  - `Repairer`: toma la primera violación, prueba planes sin preguntas del más barato al más caro desde un punto
    guardado; conserva el que hace desaparecer esa violación sin mover un pin; topes de 10 rondas y 16 ramas.
  - `Trace`: árbol de `Step` con tipo (call, primitive, repair, attempt, discarded, obligation, violation,
    planKept, planUndone), etiqueta e hijos. Un único render a líneas con sigilos (`.`, `'`, `!`, `X`, `?`, `:`).
  - `Result`: aplicado o abortado, violaciones, reparaciones, rastro, obligaciones y garantía (`guaranteed` o
    `assumed`).
- **Qué ignora.** Lenguajes, scripts, conocimiento, sesión.
- **Prueba de autonomía.** Sobre `MemoryProjection`, con una vista y un remedio escritos en el test: llega al punto
  fijo, respeta los topes, no mueve pins, deshace cuando no puede.
- **Estado actual** (después de la fase 2). `Transaction` intercepta `apply`, pliega el `Change` declarado y acumula
  pins tipados; no conoce ningún atributo. Tiene tests propios sobre la proyección en memoria (transacción y
  reparador). Queda para la fase 4: `Verifier` son tres chequeos entrelazados en una clase, la vista del compilador
  está dentro de `Repairer`, y el rastro son strings con sigilos armados al registrar.

### 5.6 `refactoring`: lenguaje de refactorings

- **Qué es.** Una biblioteca de refactorings, un runtime que los ejecuta dentro de una transacción, y el lenguaje
  `.ref` para escribirlos como composición de primitivos y de otros refactorings.
- **Para qué lo usarías.** Para que usuarios o equipos definan sus propios refactorings sin escribir Java.
- **Dominio.**
  - `Refactoring`: id, descripción, parámetros, nivel (primitive, script, composite), `apply(Runtime, Args)`.
  - `Library`: registro y búsqueda. Los primitivos se exponen como refactorings mecánicamente desde los records de
    `Primitive`; `define(text)` agrega scripts.
  - `Runtime`: lo que un refactoring ve mientras corre. `call(id, args)` con anidamiento en el rastro;
    `attempt(label, body)` con marca y verificación parcial; `oblige(assumption)`; `program()`; `rules()`. Es único:
    lo usan la sesión y `knowledge`.
  - El lenguaje: `ScriptParser`, `Stmt` (let, call, for, if, try, require, oblige, fail, return), `Expr` (literal,
    variable, función, filtro), `Functions` (consultas sobre el programa y las reglas), `Value` (Elem, Ref, Text,
    Bool, Items). La ambigüedad "¿es un id o una dirección?" se resuelve una vez, en `Args`.
  - El catálogo `standard.ref`, con presupuesto de 20 sentencias por script (`CatalogBudgetTest`).
  - `ChangeSignature`: el único compuesto escrito en Java, porque su parámetro es una lista de registros.
- **Qué ignora.** Verificación (se la pide al motor a través del runtime), lenguajes, sesión.
- **Prueba de autonomía.** Un script sobre `MemoryProjection` produce la secuencia de primitivos esperada (lo que
  hoy hace `expand`), sin verificar nada.
- **Estado actual.** `ide.refactor` y `ide.refactor.script`. El runtime es una clase anónima dentro de `Workspace`;
  `Knowledge` tiene otro propio donde `attempt` no intenta y `oblige` no obliga. Los primitivos se leen del
  vocabulario (fase 2): nada se registra a mano. Los valores de los scripts son strings. Fugas de Java en el catálogo: plantillas de cuerpo con `this.` y
  `new` (generate-getter, generate-setter, introduce-factory) y `set import` en el conocimiento.
- **Abierto.** Un lenguaje neutral de cuerpos para las plantillas (`return field`, `assign field param`,
  `return new owner(params)`) que la proyección renderice. Hasta entonces las plantillas son Java y se marcan como
  tales en el catálogo.

### 5.7 `knowledge`: planificador por metas

- **Qué es.** Dada una base de metas (qué debe valer para que un problema desaparezca) y efectos (qué hace valer un
  predicado, bajo qué condición, con qué costo, con qué preguntas), propone planes ordenados por costo con una traza
  explicable. Podría planificar otra cosa que código.
- **Dominio.** `Goal(code, predicate)`, `Effect(name, title, makes, when, for, cost, asks, do)`, `Predicate`,
  `Search` con su traza, `Plan`. Implementa `Remedy` y `QuickFix` del motor y ejecuta acciones por el `Runtime` de
  `refactoring`. ArchUnit garantiza que nadie más implementa `Remedy` ni `QuickFix`.
- **Qué ignora.** Lenguajes, texto, sesión.
- **Prueba de autonomía.** Una base de tres efectos sobre predicados inventados, sobre `MemoryProjection`.
- **Estado actual.** `ide.knowledge`. Casi autónomo: usa helpers estáticos de `Script` en vez de un evaluador de
  expresiones, y su propio `Ctx` falso.
- **Abierto.** Encadenamiento hacia atrás en varios niveles (la spec pedía 8 niveles, 4 pasos y 12 planes; hoy hay
  un nivel); `fact` e `invariant` del lenguaje de conocimiento.

### 5.8 `session`: el espacio de trabajo

- **Qué es.** El ensamblado, el único que no sería una librería ajena. Abre un proyecto, ejecuta las operaciones
  del usuario como transacciones, mantiene undo, log y estado guardado, y expone todo como capacidades.
- **Dominio.** `Session` (invoke, edit, fix, preview, expand, undo, save, diff, log), `Navigation` (callers,
  hierarchy, references, model), `Completion`, `Capabilities` (el registro construido mecánicamente desde `Library`
  y las operaciones de `Session`), `Workspace` como fachada delgada para tests y adaptadores.
- **Regla propia.** No contiene lógica de otro dominio; su prueba es la inversa de las demás: que cada línea se lea
  con los términos de los otros productos.
- **Prueba de autonomía.** Cada colaborador se testea solo; la fachada solo delega.
- **Estado actual.** `ide.workbench.Workspace`, 501 líneas con reglas, conocimiento, reparador, biblioteca, undo,
  log, guardado, transacciones, quick fixes, completion, navegación y el registro de capacidades.

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

| Módulo                                    | Al empezar la fase 2 | Al cerrar la fase 2 | Al cerrar la fase 3 |
|-------------------------------------------|----------------------|---------------------|---------------------|
| program                                   | 8                    | 12                  | 12                  |
| projection-kit                            | (no existía)         | 8                   | 8                   |
| java                                      | 0                    | 8                   | 24                  |
| engine                                    | 0                    | 9                   | 9                   |
| refactoring                               | 1                    | 3                   | 3                   |
| core, projection, knowledge               | 0                    | 0                   | 0                   |
| session                                   | 13                   | 13                  | 13                  |
| acceptance                                | 102                  | 103                 | 104                 |

## 7. Decisiones

| #  | Tema                     | Decisión                                                                                   | Estado      |
|----|--------------------------|--------------------------------------------------------------------------------------------|-------------|
| 1  | Mapa y nombres           | core, program, projection, java, engine, refactoring, knowledge, session, adapters          | tomada, 2026-10-31 (10610bc) |
| 2  | Forma de los primitivos  | `Primitive` sellado y `apply(Primitive)` que devuelve `Change`; cada primitivo declara su intención | tomada, 2026-10-31 (1524f0c) |
| 3  | Vínculos                 | extends, implements y declared son referencias; `Link` reemplaza los `set` de tipos        | tomada, 2026-10-31 (4d16bfe, 5cceb1a) |
| 4  | Identidad                | `Address` tipada con ids de dueño y de tipos de parámetro; reconciliación estructural después | tomada, 2026-10-31 (67901c9) |
| 5  | Edición                  | solo `EditSet` de texto; sin mutación del AST ni printer léxico                            | tomada, 2026-10-31 (3f994c2) |
| 6  | Hechos                   | conjunto cerrado y tipado (claves `Fact`; los valores siguen siendo texto)                 | tomada, 2026-10-31 (4d16bfe) |
| 7  | Cuerpos                  | fuera del grafo; el fragmento es una dirección opaca de la proyección (líneas en Java, posiciones de usos en memoria) | tomada, 2026-10-31 (f64f7dd) |
| 8  | Creación                 | `Create` recibe los hechos con que nace y, para un parámetro, el argumento de las llamadas  | tomada, 2026-10-31 (8f0ed6a) |
| 9  | Rastro                   | `Trace` como árbol de datos; un único render a líneas                                      | propuesta   |
| 10 | Conformidad              | `projection-kit`: `MemoryProjection` y `ProjectionContract`, que corre contra cada driver   | tomada, 2026-10-31 (f64f7dd) |
| 11 | Referencias no resueltas | entran al grafo con destino desconocido; import pasa a ser retarget                        | diferida, etapa 2 |
| 12 | Cuerpos en plantillas    | lenguaje neutral de cuerpos renderizado por la proyección                                  | diferida, etapa 2 |
| 13 | Módulos Maven            | un módulo por producto, más `acceptance` para los escenarios transversales                  | tomada, 2026-10-31 (5d641a2) |
| 14 | Tests                    | producto, contrato y aceptación, en doble ciclo (sección 6)                                | tomada, 2026-10-31 |

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
| `set` como switch de operaciones (marcadores, `default` y `order` resueltos en 8f0ed6a; quedan `import` e `initializer`) | `ide.java.primitives.SetAttribute` | etapa 2 |
| `MemoryProjection` en un solo archivo grande (resuelto en 9c507a0)                  | `projection-kit`       | 3    |
| Búsqueda de declaración, unidad y ruta repetida por primitivo (resuelto en 646c9bc) | `ide.java`             | 3    |
| Vistas no enchufables; vista del compilador dentro del reparador                   | `ide.coherence`        | 4    |
| Rastro como strings con sigilos                                                    | `ide.coherence`        | 4    |
| Runtime de refactorings como clase anónima de la sesión; `Ctx` falso en knowledge  | `ide.workbench`, `ide.knowledge` | 5 |
| Valores de scripts como strings; id o dirección resuelto en cada función           | `ide.refactor.script`  | 5    |
| Plantillas de cuerpo en Java dentro del catálogo; `set import`                     | `standard.ref`, `knowledge.ref` | etapa 2 |
| Workspace como cajón de todo                                                       | `ide.workbench`        | 6    |

## 9. Plan de acción

### Reglas de la migración

- Estrangulación, nunca big-bang: cada commit deja los 121 tests en verde. Un commit por ciclo.
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

### Fase 4: vistas y rastro en `engine`

- Objetivo: `View` enchufable, `Trace` como datos.
- Tests rojos: `ViewsTest` (cada vista sola sobre dos grafos armados a mano); `TraceTest` (árbol y render);
  `StepsTest` sigue pasando sin cambios.
- Qué se toca: `Verifier` como lista de vistas; la vista del compilador sale del `Repairer`; `Result` lleva el
  árbol; `why` y `why-repair` devuelven el árbol y lo renderizan en el borde.
- Criterio de cierre: agregar una vista es agregar una clase; los strings con sigilos existen en un solo lugar.

### Fase 5: `refactoring` y `knowledge` como productos

- Objetivo: `Library` y `Runtime` propios; valores tipados; conocimiento sobre el runtime real.
- Tests rojos: `RuntimeTest` en `refactoring`, sobre `MemoryProjection` (expand de encapsulate-field da la
  secuencia esperada; semántica de attempt y oblige); `KnowledgeTest` en `knowledge`, sobre `MemoryProjection` con
  efectos inventados; ArchUnit: `knowledge` no tiene `Ctx` propio.
- Qué se toca: el runtime sale de `Workspace`; `Knowledge` usa el runtime real; `Value` y `Args` resuelven
  id-o-dirección una vez; las plantillas Java del catálogo quedan marcadas como fuga conocida.
- Criterio de cierre: `Workspace` no tiene ningún `Ctx`; un script se prueba sin motor.

### Fase 6: `session`

- Objetivo: separar `Session`, `Navigation`, `Completion` y `Capabilities`; `Workspace` como fachada.
- Tests rojos: los actuales de `workbench` siguen pasando contra la fachada; un test por colaborador en aislamiento.
- Criterio de cierre: cada clase de `session` se lee con los términos de los otros productos; la fachada solo delega.

### Etapa 2, por valor

1. Referencias no resueltas en el grafo: el quick fix de import se vuelve un retarget, `binding.unresolved` y
   completion comparten modelo.
2. Reconciliación estructural de identidad en ediciones manuales (hoy un rename a mano equivale a borrar y crear).
3. Invalidación selectiva del grafo.
4. Lenguaje neutral de cuerpos para plantillas; se van las fugas de Java del catálogo.
5. Lo que falta del catálogo: pull-up-unifying, borrado de tipos anidados, encadenamiento en varios niveles del
   conocimiento.
6. Adaptadores: CLI, luego UI, luego MCP, cada uno como módulo que declara solo `session` y `core`.

## 10. Glosario

- **Elemento**: algo con identidad en el programa: paquete, tipo, campo, constructor, método, parámetro.
- **Referencia**: un uso o un vínculo de un elemento (site) a otro (target), con un modo.
- **Vínculo**: una referencia de declaración: extends, implements, declared.
- **Hecho**: un valor tipado sobre un elemento: visibilidad, estático, abstracto, final.
- **Dirección**: cómo se nombra un elemento desde el texto; cambia con rename y move. El id no.
- **Identidad**: la tabla dirección → id; solo los primitivos la transfieren.
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
- **Runtime**: lo que un refactoring ve mientras corre dentro de una transacción.
- **Capacidad**: esquema más invocación; lo único que los adaptadores conocen.
- **Test de producto**: usa un producto solo, en su vocabulario, con dobles de lo que necesita de afuera.
- **Test de contrato**: verifica que cada implementación de una interfaz cumple lo que sus usuarios asumen.
- **Test de aceptación**: un escenario de usuario que atraviesa todos los productos.
