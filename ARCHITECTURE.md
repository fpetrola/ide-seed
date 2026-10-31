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
10. Las dependencias van en un solo sentido y ArchUnit las hace cumplir; una regla nueva de ArchUnit es el primer
    test rojo de cada fase de migración.
11. Un producto se testea solo, con una implementación falsa de lo que necesita.
12. Tipos, no strings: todo lo que el motor compara o consulta tiene un tipo propio (ids, modos, hechos,
    direcciones, pasos del rastro). El texto aparece solo en la proyección y en los renders.

## 4. Mapa de módulos

```
                 adapters (cli, ui, mcp)
                          |
                       session
        _______________/  |  \________________
       /                  |                   \
  knowledge            refactoring          (navegación, completion)
       \__________________|
                       engine
                          |
                      projection  <------ java (el driver de Java)
                          |
                       program
                          |
                        core
```

Reglas de dependencia:

- Todos conocen `core` y `program`.
- `java` implementa `projection` y no conoce nada de arriba.
- `engine` conoce `projection` y nunca `java`.
- `refactoring` conoce `engine` (a través de su runtime) y nunca `java`.
- `knowledge` conoce `refactoring` y `engine`; nadie de abajo conoce `knowledge`.
- `session` conoce todo; los adaptadores conocen solo `session` y `core`.
- Solo `java` conoce JavaParser y javac.

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

Un solo módulo Maven por ahora; paquetes y ArchUnit marcan las fronteras. Los módulos Maven llegan cuando las
interfaces dejen de moverse.

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
- **Estado actual.** `ide.graph`. Intrusiones: `superclass`, `implements` y `declared` como strings en un mapa;
  `Reference.mode` string; la firma se obtiene cortando la dirección en `#`; consultas lineales (`find`,
  `referencesTo`, `isSubclass`) llamadas dentro de bucles; `Program` es el nombre de la interfaz mutable; la
  comparación de referencias vive en el Verifier; `Identity` está en `ide.java`, transfiere por prefijo de string y
  arregla constructores con un regex.
- **Abierto.** Genéricos en las claves de tipo (hoy el nombre simple con `<>` como texto); constantes de enum (no se
  modelan); variables locales como elementos (no, hasta que un refactoring lo pida).

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
  - `files()`, `source(path)`.
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
- **`MemoryProjection`** (en tests): una proyección sin lenguaje sobre un grafo mutable. Aplica los primitivos al
  grafo y declara los mismos `Change`. Es lo que permite probar `engine`, `refactoring` y `knowledge` solos, y es la
  implementación de referencia del contrato.
- **Qué ignora.** Refactorings, transacciones, conocimiento, sesión.
- **Estado actual.** No existe como módulo. La interfaz `ide.graph.Program` mezcla nueve primitivos con firmas
  distintas, un checkpoint sin tipo, diagnósticos y completion; `Transaction` deduce la intención de cada primitivo
  desde el nombre del atributo.
- **Abierto.** Si `diagnostics` y `completion` quedan en el mismo contrato o en uno de análisis aparte: juntos
  hasta que estorbe.

### 5.4 `java`: editor estructural de Java

- **Qué es.** Una librería que, dado un conjunto de fuentes Java en memoria, lee el programa como grafo y ejecuta
  primitivos como ediciones mínimas de texto, conservando formato y comentarios. La única que conoce JavaParser y
  javac.
- **Para qué lo usarías.** Codemods, migraciones masivas, cualquier automatización sobre un codebase Java, sin
  verificación ni scripts.
- **Dominio**, en capas internas, cada una con su vocabulario:
  1. Texto. `Unit` (ruta, texto, AST parseado con posiciones), `Edit` (reemplazo por rango), `EditSet` (ediciones
     por unidad, aplicadas de atrás hacia adelante, reparseo solo de la unidad tocada). Es la única vía de cambio:
     no hay mutación in-place del AST ni printer con preservación léxica; el texto es el que tenemos.
  2. Sintaxis. `Decl` (handle a la declaración de un elemento: unidad, nodo, rango, modificadores, cabecera, cuerpo,
     punto de inserción), `Render` (texto de un miembro nuevo, un archivo nuevo, un import, una llamada, con la
     indentación del contexto), `Imports`.
  3. Semántica. `Resolution` (a qué resuelve cada uso), `Reader` (grafo desde las unidades, en dos pasadas:
     elementos y después referencias), `WorkspaceTypeSolver`, `JavacCheck`, `Bodies` (flujo de datos y precedencia
     para extract e inline).
  4. Primitivos. Una clase por primitivo, todas con la misma forma: precondiciones, ediciones, transferencia de
     identidad, `Change` declarado.
  5. Fachada. `JavaProjection`: unidades, identidad, lectura cacheada, invalidación, instantáneas. Unas 150 líneas.
- **Qué ignora.** Transacciones, verificación, refactorings, scripts, sesión.
- **Prueba de autonomía.** Aplicar un primitivo sobre texto y comparar texto; el kit de conformidad de `projection`.
- **Estado actual.** `ide.java`. `JavaProject` tiene 1066 líneas con los primitivos como métodos. Conviven dos
  mecanismos de edición: mutación del AST seguida de `changed()`, que reimprime y reparsea todas las unidades, y
  empalme de texto. `set` es un switch de doce casos, varios de los cuales son operaciones y no atributos (`import`,
  `order`, `default`). Los defaults de parámetros nuevos se resuelven con marcadores de texto. La búsqueda de la
  declaración, de la unidad y de la ruta se repite en cada primitivo. La identidad se transfiere por prefijos de
  string. Bug conocido y reproducido: renombrar un tipo que otra clase usa como tipo de parámetro aborta con un
  `binding.unresolved` falso, porque la dirección de ese método cambia sin que nadie transfiera su id.
- **Abierto.** Invalidación selectiva del grafo (hoy se reconstruye entero; rename sobre 2400 elementos tarda unos
  4 s); borrado de tipos anidados; reconciliación estructural de identidad en `edit`.

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
- **Estado actual.** `ide.coherence`. `Transaction` tiene casos por nombre de atributo (superclass, type,
  visibility). `Verifier` es tres chequeos entrelazados en una clase. La vista del compilador está dentro de
  `Repairer`. El rastro son strings con sigilos e indentación armados al registrar, y `Result` lleva líneas.

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
  `Knowledge` tiene otro propio donde `attempt` no intenta y `oblige` no obliga. `Primitives.all()` registra a
  mano. Los valores de los scripts son strings. Fugas de Java en el catálogo: plantillas de cuerpo con `this.` y
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

## 6. Decisiones

| #  | Tema                     | Decisión                                                                                   | Estado      |
|----|--------------------------|--------------------------------------------------------------------------------------------|-------------|
| 1  | Mapa y nombres           | core, program, projection, java, engine, refactoring, knowledge, session, adapters          | acordado, ajustable |
| 2  | Forma de los primitivos  | `Primitive` sellado y `apply(Primitive)` que devuelve `Change`                             | propuesta   |
| 3  | Vínculos                 | extends, implements y declared son referencias; `Link` reemplaza los `set` de tipos        | propuesta   |
| 4  | Identidad                | `Address` tipada con ids de dueño y de tipos de parámetro; reconciliación estructural después | propuesta |
| 5  | Edición                  | solo `EditSet` de texto; sin mutación del AST ni printer léxico                            | propuesta   |
| 6  | Hechos                   | conjunto cerrado y tipado                                                                  | propuesta   |
| 7  | Cuerpos                  | fuera del grafo; el fragmento es una dirección opaca de la proyección                      | propuesta   |
| 8  | Creación                 | `Create` recibe la especificación inicial (type, default, body)                            | propuesta   |
| 9  | Rastro                   | `Trace` como árbol de datos; un único render a líneas                                      | propuesta   |
| 10 | Conformidad              | `MemoryProjection` más un kit de conformidad que corre contra cada driver                  | propuesta   |
| 11 | Referencias no resueltas | entran al grafo con destino desconocido; import pasa a ser retarget                        | diferida, etapa 2 |
| 12 | Cuerpos en plantillas    | lenguaje neutral de cuerpos renderizado por la proyección                                  | diferida, etapa 2 |
| 13 | Módulos Maven            | cuando las interfaces dejen de moverse                                                     | diferida    |

Una decisión "propuesta" se aplica salvo objeción; al aplicarse pasa a "tomada" con la fecha y el commit.

## 7. Intrusiones conocidas

Registro de deuda de diseño, con la fase que la resuelve.

| Intrusión                                                                          | Dónde                  | Fase |
|------------------------------------------------------------------------------------|------------------------|------|
| Vínculos como strings, modos como strings, firma cortando la dirección            | `ide.graph`            | 1    |
| Consultas lineales dentro de bucles                                                | `ide.graph`            | 1    |
| Comparación de referencias dentro del Verifier                                     | `ide.coherence`        | 1    |
| Identidad por prefijos de string y regex; bug de rename de tipo usado como parámetro | `ide.java.Identity`  | 1 y 3 |
| Interfaz mutable con nueve firmas y checkpoint sin tipo                            | `ide.graph.Program`    | 2    |
| La Transaction interpreta atributos para deducir la intención                      | `ide.coherence`        | 2    |
| Registro manual de los primitivos como refactorings                                | `ide.refactor`         | 2    |
| Dos mecanismos de edición; `changed()` reimprime todas las unidades                | `ide.java.JavaProject` | 3    |
| `set` como switch de doce operaciones; marcadores de texto para defaults           | `ide.java.JavaProject` | 3    |
| Búsqueda de declaración, unidad y ruta repetida por primitivo                      | `ide.java.JavaProject` | 3    |
| Vistas no enchufables; vista del compilador dentro del reparador                   | `ide.coherence`        | 4    |
| Rastro como strings con sigilos                                                    | `ide.coherence`        | 4    |
| Runtime de refactorings como clase anónima de la sesión; `Ctx` falso en knowledge  | `ide.workbench`, `ide.knowledge` | 5 |
| Valores de scripts como strings; id o dirección resuelto en cada función           | `ide.refactor.script`  | 5    |
| Plantillas de cuerpo en Java dentro del catálogo; `set import`                     | `standard.ref`, `knowledge.ref` | etapa 2 |
| Workspace como cajón de todo                                                       | `ide.workbench`        | 6    |

## 8. Plan de acción

### Reglas de la migración

- Estrangulación, nunca big-bang: cada commit deja los 121 tests en verde. Un commit por ciclo.
- El orden sigue las dependencias, de abajo hacia arriba, con una excepción: la proyección falsa entra temprano
  (fase 2) porque habilita los tests de autonomía de todo lo demás.
- Cada fase abre con la regla de ArchUnit que la define, en rojo, y cierra con el test de autonomía del producto en
  verde.
- Los tests de aceptación (los que invocan refactorings y comparan texto) no se tocan. Los que miran el rastro
  siguen pasando por el render.
- `PerformanceProbe` al cierre de cada fase: el rename sobre `OrdersBig` no empeora.
- Al cerrar una fase se actualiza el "Estado actual" de las fichas tocadas y el estado de las decisiones aplicadas.

### Fase 1: `program` como producto

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

### Fase 2: el contrato `projection` y el fin de la interpretación en el motor

- Objetivo: `Primitive` y `Change`; `Projection` con `apply`; `MemoryProjection`; kit de conformidad; `Transaction`
  que pliega lo que el `Change` declara.
- Tests rojos:
  - ArchUnit: `engine` no depende de `java`; `projection` depende solo de `program` y `core`.
  - `ProjectionContract`, abstracto, con las cinco obligaciones del driver, instanciado para `MemoryProjection` y
    para `JavaProjection`.
  - `EngineTest` sobre `MemoryProjection`: una vista y un remedio del test; punto fijo, topes, pins.
  - `TransactionTest`: las expectativas salen del `Change`, no de nombres de atributo.
- Qué se toca: `JavaProject` implementa `apply` delegando a sus métodos actuales y declarando los `Change` (la
  lógica de intención se muda de `Transaction` a cada primitivo de Java); `Primitives.all()` se genera desde el tipo
  sellado; `Set` de tipos pasa a `Link`; `set order` pasa a `Move`; `set default` entra en `Create`.
- Criterio de cierre: `Transaction` no menciona ningún atributo; `Primitives` no registra nada a mano; el motor
  corre entero contra la proyección falsa.

### Fase 3: `java` en capas

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
- Tests rojos: ArchUnit (`refactoring` no depende de `session`; `knowledge` no tiene `Ctx` propio);
  `RuntimeTest` sobre `MemoryProjection` (expand de encapsulate-field da la secuencia esperada; semántica de
  attempt y oblige); `KnowledgeTest` sobre `MemoryProjection` con efectos inventados.
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
6. Módulos Maven: base (core, program, projection, java, engine), refactorings, remedies (knowledge), app
   (session, adapters).
7. Adaptadores: CLI, luego UI, luego MCP.

## 9. Glosario

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
