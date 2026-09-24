# METIS rules language v1 — compiler contract (M1)

## Delivered boundary

M1 supplies a standalone schema/semantic compiler, immutable typed execution plans,
a pure expression evaluator, a versioned operation registry and automated conformance
tests. **M2 now executes these definitions headlessly**, including state transitions,
resumable decisions and replay; see [RULES_RUNTIME_M2.md](RULES_RUNTIME_M2.md).
M3 now publishes and persists these sessions through [RULES_API_M3.md](RULES_API_M3.md).
Existing catalogue APIs and captured
`activation-v1`/`legacy-v1` sessions continue through their explicit existing contracts.
M4 adds optional mechanical configuration and module handlers, documented in
`RULES_MECHANICS_M4.md`; core-only captured fingerprints remain supported.

Verified locally on 2026-09-20 with the combined clean full/mutation command:
208 Java tests, 200 generated property cases, package coverage gates, 77/79 focused
mutants detected, SQL/API/Chromium regressions and the intentional failure/cleanup probe.

The schema is packaged at
`engine-core/src/main/resources/rules/language-v1.schema.json`. Complete examples:

- [`resource-race.json`](engine-core/src/main/resources/rules/examples/resource-race.json):
  owned score counters, paid boosts, alternate turns and a score-based ending.
- [`hidden-grid.json`](engine-core/src/main/resources/rules/examples/hidden-grid.json):
  private setup choices, owned hidden cells, repeated-guess rejection, hit events and
  elimination. Cell addressing uses typed strings/sets, with no battle movement or dice.

## Compile a definition

From the repository root (Java 21/Maven):

```text
mvn -q -pl engine-core -Prules-compile compile exec:java
mvn -q -pl engine-core -Prules-compile "-Drules.source=engine-core/src/main/resources/rules/examples/hidden-grid.json" compile exec:java
```

The first command uses the resource-race example. `rules.source` accepts a file path
relative to the working directory or an absolute path. Output includes validity,
diagnostics, module pins, compiler version, fingerprint and procedure instruction
bounds. Invalid definitions produce diagnostics and a nonzero command exit.

Java entry points are in `com.metis.engine.core.rules`:

```java
var result = new RulesCompiler().compile(json);
if (!result.valid()) {
    // Return structured diagnostics to an author; do not execute an invalid plan.
    result.diagnostics().forEach(System.out::println);
} else {
    CompiledRules plan = result.definition();
    System.out.println(plan.fingerprint());
}
```

The compiler requires no Spring application, database, game identity switch or UI.

## Document structure

All root sections are explicit; empty maps are allowed where appropriate:

| Section | Meaning |
| --- | --- |
| `languageVersion` | Exactly integer `1` |
| `id`, `revision` | Definition identity and immutable author revision |
| `modules` | Exact registered versions, e.g. `{"core":"1"}`; core is required |
| `limits` | `maxInstructions`, `maxCollectionSize`, `maxCallDepth` |
| `state` | Typed global fields with initial values and visibility |
| `entities` | Definition-owned entity types, ownership, attributes and resources |
| `events` | Event payload types and visibility |
| `procedures` | Explicit parameter types and ordered instruction arrays |
| `actions` | Input parameter types, boolean eligibility, procedure reference and arguments |
| `setup` | Reference to a procedure requiring no arguments |
| `windows` (optional) | Typed ordered reaction-window declarations; see M3 |
| `dice`, `resultTables`, `decks`, `effects` (optional) | Configuration for explicitly pinned M4 modules |
| `flow` | Initial phase, repeating-round start and phase definitions |

Identifiers match `[a-z][a-zA-Z0-9_-]{0,63}`. Module identifiers use lowercase letters,
digits, `_` and `-`; operation names are qualified, e.g. `core.setAttribute`.
Unknown fields fail validation rather than being ignored. JSON Schema checks structure;
the registry and semantic compiler additionally validate instruction fields, bindings,
argument types and cross-references. Schema validation alone is not sufficient.

## Types, initial values and ownership

Types are `integer`, `decimal`, `boolean`, `string`, `player`, `entity:<declared-type>`
and recursive list types such as `{"list":"integer"}`. Lists always have a declared
element type, including empty lists. Null is not a value or a missing-value default.

Fields declare `type`, `initial`, `visibility`. Entity types declare:

```json
{
  "ownership":"PLAYER",
  "attributes":{"score":{"type":"integer","initial":0,"visibility":"PUBLIC"}},
  "resources":{"energy":{"initial":2,"minimum":0,"maximum":20,"visibility":"PUBLIC"}}
}
```

Ownership is `PLAYER` or `NONE`. Visibility is `PUBLIC`, `OWNER` or `SERVER`;
`OWNER` attributes/resources require a player-owned entity. Global fields cannot
use `OWNER` because they have no owner. Resource counts/bounds are signed 64-bit
integers; initial counts must fit the declared bounds. Negative ranges are allowed
when a game explicitly models debt or another signed resource.

Player/entity values are typed references rather than interchangeable strings. Initial
reference values use string IDs plus their declared type. The compiler validates the
type name; existence of the referenced runtime entity/player belongs to execution.
Entity reads in the pure evaluator already fail explicitly when the entity is absent.

There are no implicit hull, shield, weapon, command, dice, board or faction fields.
Traits can be modeled as boolean attributes or typed string lists and selected through
`get`, `contains` and `filter`; their names carry no built-in mechanical meaning.

## Expressions

Scalar JSON numbers, booleans and strings are literal expressions. Other forms:

| Form | Result |
| --- | --- |
| `{"var":"name"}` | Explicit in-scope binding |
| `{"state":"field"}` | Typed global state value |
| `{"entities":"counter"}` | References of that type in stable ID order |
| `{"owner": entityExpression}` | Player owner; requires a player-owned concrete type |
| `{"get":{"entity":expr,"field":"score"}}` | Declared entity attribute |
| `{"resource":{"entity":expr,"field":"energy"}}` | Declared integer resource |
| `{"list":{"elementType":"integer","items":[1,2]}}` | Typed immutable list |
| `{"filter":{"source":expr,"as":"item","where":expr}}` | Order-preserving selection with boolean predicate |
| `{"map":{"source":expr,"as":"item","value":expr}}` | Order-preserving typed projection |
| `{"op":"add","args":[expr,expr]}` | Typed registered core expression operation |
| `{"query":{"op":"module.query","args":{"name":expr}}}` | Typed result from a pinned pure mechanical query |

Operators: `add`, `sub`, `mul`, `div`, `floor`, `ceil`, `eq`, `ne`, `lt`, `lte`, `gt`,
`gte`, `and`, `or`, `not`, `count`, `sum`, `contains`, `at`, `append`, `id`, `concat`.

- `and`/`or` short-circuit left to right. They and `concat` accept two or more arguments.
- `at` uses a zero-based integer index; invalid indices fail, with no implicit null.
- `id` converts a typed player/entity reference to its string identity.
- `append` preserves order and must fit the list element type and collection limit.
- Arithmetic/comparisons require numbers; equality requires compatible types. Numeric
  equality ignores decimal spelling; list equality compares compatible typed values.
- Integers are signed 64-bit with checked overflow. Integer-to-decimal promotion is
  allowed; decimal-to-integer coercion requires explicit `floor` or `ceil`.
- Decimal arithmetic uses 34 significant digits (`DECIMAL128`, half-even arithmetic).
  Normalized decimal scale must lie in `[-128,128]`. Division always returns decimal;
  division by zero and integral overflow fail. `floor`/`ceil` return checked integers.
- Strings are bounded to 4096 characters. Collections and recursive value checking
  consume the evaluator budget; no random, clock, IO or state-mutating expressions exist.

Mechanical queries must declare a result, exactly the `PURE` effect and a `PURE` or
`READ_ONLY` preview policy. Their arguments are checked/coerced to the registered
signature. `EvaluationContext` supplies named trusted query handlers with immutable
argument/entity maps; absent handlers fail as `QUERY_UNAVAILABLE`. This is the extension
point used by M5's spatial queries. Native modules must honor their purity/work bounds;
module conformance tests, not a metadata flag alone, establish implementation correctness.

`Expressions.evaluate(expression, EvaluationContext)` receives immutable variable,
state and entity maps and explicit limits. Its only mutable bookkeeping is a supplied
evaluation budget. Missing bindings/fields are errors, not zero/false values. The caller
must provide the declared context; audience projection and actor authentication remain
execution/API responsibilities rather than privileges granted by the evaluator.

## Procedures, scope and instructions

Every procedure declares `parameters` and `steps`. `round: integer` and
`players: list<player>` are supplied context bindings. Procedure parameters are explicit;
there is no implicit actor. Actions additionally have `actor: player`, supplied by the
future authoritative execution boundary, not a client input parameter.

Bindings cannot shadow parameters/context or earlier locals. `let` and `choose` bind
subsequent instructions in the same scope. `forEach`, `filter` and `map` have local
iteration bindings. Branch/loop locals do not escape their block. Calls require exact
named arguments, have no implicit return binding and cannot form recursive dependencies.

| Instruction | Required fields besides `op` | Optional fields |
| --- | --- | --- |
| `core.let` | `name`, `value` | — |
| `core.if` | boolean `condition`, instruction arrays `then`, `else` | — |
| `core.forEach` | list `source`, `as`, positive `maxItems`, `steps` | — |
| `core.call` | `procedure`, expression map `args` | — |
| `core.window` | `window`, expression map `args` | — |
| `core.choose` | player `player`, list `options`, `as`, `visibility` (`PUBLIC`/`OWNER`) | — |
| `core.setState` | `field`, typed `value` | — |
| `core.setAttribute` | `entity`, `field`, typed `value` | — |
| `core.modifyResource` | `entity`, `resource`, integer `delta` | — |
| `core.createEntity` | `type`, string `id`, `attributes`, `resources`, `as` | `owner`, required exactly for player-owned types |
| `core.removeEntity` | `entity` | — |
| `core.emit` | `event`, exact typed `values` | `to`, required exactly for OWNER events |
| `core.endGame` | string `outcome`, list-of-player `winners` | — |

Entity creation uses declared initial values for fields/resources without overrides.
Initial declarations are checked at compile time; dynamic resource bounds, choices,
ownership, target existence and action eligibility are also enforced by M2 execution.
Compilation itself produces plans; effects and decisions run only through the interpreter.

Compiled instructions retain their operation, typed inputs, references, nested blocks,
binding and source JSON Pointer. They contain no mutable JSON nodes. Procedure bounds
count instructions after call expansion; branches use the larger branch, bounded loops
use `maxItems`, and calls include callee cost. Exceeding limits or call depth is rejected
at compilation. Expression/data-dependent work additionally needs the runtime budget;
the static instruction bound is not a guarantee that arbitrary inputs fit that budget.

## Flow policies

`flow` declares `initial`, `roundStart` and `phases`. Each phase supplies `controller`,
`groupSize`, ordered `steps`, `next`, and optionally `onEnter` (a no-argument procedure).
Steps have an `id`, action-ID list, `minimum` and `maximum` budget.

Controllers are `ALTERNATING_PLAYERS`, `ALTERNATING_ENTITIES`, `PLAYER_PHASE`,
`SIMULTANEOUS`, `MANUAL`, `AUTOMATIC`. Entity controllers additionally require an
`entities` expression returning concrete entity references; its scope includes actor.
Other controllers require group size 1. Automatic phases have no player action steps;
manual phases may have none. Player/entity phases require steps.

Entity controllers also require `entityParameter`, naming an entity-typed parameter on
each permitted action. M2 uses it to bind the selected active entity to the submitted
action. This binding was introduced in `rules-compiler-2`. Core-only captures retain
M3's `rules-compiler-3` / `rules-runtime-2`; mechanical captures use M4's 4/3 contracts.

All phases must be reachable; round start must lie on the repeating cycle. Ordinary
round cycles are legal, but automatic-only cycles are rejected. Phase entry procedures
cannot require arguments. Phase IDs have no built-in game meaning. Handover, budget
consumption and phase transitions now execute under the policies documented in M2.

## Modules and deterministic compilation

The standard registry supplies `core@1`. Java extensions register a module ID/version
and typed mechanical operations, each with named arguments, optional typed result,
effect metadata and preview policy. Invocation is `{ "op":"module.name", "args":{...},
"as":"result" }`; `as` is present exactly when the operation returns a value.
Control instructions describe their own effects, not a transitive summary of children
or callees; their preview policy is `NONE`. Eligibility expressions remain a separate
pure language, including explicitly pure registered queries.

Registry-only `RuleType.ENTITY` accepts any entity type so mechanical operations need
not name a game's types. Attribute/owner/resource access still requires a concrete
declared type; generic results do not bypass type checks. Registering a contract does
not implement an execution handler. M2/M3 execute core operations and bounded timing
windows; M4 installs dice, cards, resources, effects and queues. M5 installs space/scenario
modules and projected finite action choices; see `RULES_SPATIAL_M5.md` for compiler5/runtime4
selection, movement preview and supported geometric semantics.
An unavailable module/version/operation fails compilation or runtime capability checks.

The SHA-256 fingerprint covers compiler version, canonical source and selected module
contracts. Canonicalization sorts object keys, preserves every array's order and
normalizes decimal spelling. Integer and decimal expression literals remain distinct
(`1` versus `1.0`); `1.0` and `1.00` normalize alike. Whitespace and object-key ordering
do not affect the fingerprint. Changed rule data, array order or module contracts do.

## Diagnostics and resource limits

Each diagnostic has `code`, `definitionId`, `ruleId` where applicable, `path` (JSON
Pointer), and `message`. Structural errors are sorted and capped at 100; semantic
validation reports the first error in deterministic traversal order. No partial plan
is returned when validation fails.

Examples: `JSON_SYNTAX`, `LANGUAGE_VERSION`, `SCHEMA_INVALID`, `MODULE_VERSION`,
`OPERATION_UNKNOWN`, `TYPE_MISMATCH`, `BINDING_UNKNOWN`, `BINDING_EXISTS`,
`PROCEDURE_RECURSION`, `CALL_DEPTH`, `INSTRUCTION_LIMIT`, `AUTOMATIC_CYCLE`.
Expression failures include `MISSING_VALUE`, `ENTITY_NOT_FOUND`, `NUMERIC_ERROR`,
`VALUE_BOUNDS`, `INDEX_OUT_OF_BOUNDS`, `COLLECTION_LIMIT`, `EVALUATION_LIMIT`.

Input limits: 1,000,000 characters, JSON nesting 64, strings 4096, numeric tokens 256
characters, and 10,000 source instructions across procedures. Definitions choose
smaller execution limits within hard maxima: 100,000 instructions, 1024 collection
items/entities and 32 procedure call frames. Repeated JSON keys and trailing documents
are rejected. Runtime limits are re-enforced during effects and decisions. M3 adds
`maxWindowDepth` (default 8, maximum 16) and explicit per-window responder bounds.

## Automated acceptance

```text
mvn -B -pl engine-core verify
mvn -B -pl engine-core -Prules-mutation clean verify
mvn -B "-Prules-full,rules-mutation" clean verify
```

Tests include complete example compilation, reviewed negative-definition fixtures,
independent expression vectors, all-core-operation conformance, deterministic generated
arithmetic/list properties, canonical fingerprints, renaming, immutability, scoping,
recursion, phase cycles, resource limits and CLI failures. The new package has enforced
90% line / 85% branch coverage gates. The focused mutation profile enforces 80% on
type compatibility, validation and expression condition/boundary changes. The combined
command also runs the existing isolated SQL/API/browser suite and cleanup probe.

CI includes the mutation job. Hosted CI results are separate from local verification.
M2 adds procedure simulation and checkpoint/replay tests; M3 adds durable API/database
recovery and reaction-window tests. These milestones do not claim complete licensed-game rules conformance.
