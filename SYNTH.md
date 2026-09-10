# Synth Example Run

LLM produced workflow:
```json
{
  "towl": "v1",
  "description": "list classes in the latest jackson-databind that involve polymorphic type validation",
  "let": {
    "ver": {
      "source": {
        "call": {
          "operation": "get_latest_version",
          "args": {
            "groupId": "com.fasterxml.jackson.core",
            "artifactId": "jackson-databind"
          }
        }
      }
    },
    "symbols": {
      "source": {
        "call": {
          "operation": "list_javadoc_symbols",
          "args": {
            "groupId": "com.fasterxml.jackson.core",
            "artifactId": "jackson-databind",
            "version": {
              "ref": "ver",
              "path": "result"
            }
          }
        }
      },
      "filter": {
        "contains": [
          {
            "path": "fqn"
          },
          "Polymorphic"
        ]
      }
    },
    "classes": {
      "forEach": {
        "sym": {
          "from": {
            "ref": "symbols"
          },
          "source": {
            "call": {
              "operation": "get_javadoc_symbol",
              "args": {
                "groupId": "com.fasterxml.jackson.core",
                "artifactId": "jackson-databind",
                "version": {
                  "ref": "ver",
                  "path": "result"
                },
                "link": {
                  "ref": "sym",
                  "path": "link"
                }
              }
            }
          },
          "result": {
            "class": {
              "afterLast": [
                {
                  "ref": "sym",
                  "path": "fqn"
                },
                "."
              ]
            },
            "summary": {
              "take": [
                {
                  "path": ""
                },
                200
              ]
            }
          }
        }
      },
      "onError": "collect"
    }
  },
  "result": {
    "ref": "classes"
  }
}
```

Workflow log:
```
[synth] explain:
plan: list classes in the latest jackson-databind that involve polymorphic type validation
wave 1: ver
wave 2: symbols
wave 3: classes
ver:
  call get_latest_version [ONE] effects=[unknown] runs once
symbols:
  call list_javadoc_symbols [MANY] effects=[unknown] runs once
    filter: local predicate
classes:
  forEach sym (onError=collect), body runs once per element
    call get_javadoc_symbol [ONE] effects=[unknown] runs once per sym
      result: maps each element
value: explicit result

[towl] call -> get_latest_version args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind}
[towl] call <- get_latest_version
[towl] call -> list_javadoc_symbols args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2}
[towl] call <- list_javadoc_symbols
[towl] source streamed 618 element(s)
[towl] filter kept 7/618 element(s)
[towl] forEach 'sym' over 7 element(s) (maxConcurrency=6)
[towl] call -> get_javadoc_symbol args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2, link=com/fasterxml/jackson/databind/jsontype/BasicPolymorphicTypeValidator.NameMatcher.html}
[towl] call -> get_javadoc_symbol args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2, link=com/fasterxml/jackson/databind/jsontype/BasicPolymorphicTypeValidator.html}
[towl] call -> get_javadoc_symbol args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2, link=com/fasterxml/jackson/databind/jsontype/PolymorphicTypeValidator.Base.html}
[towl] call -> get_javadoc_symbol args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2, link=com/fasterxml/jackson/databind/jsontype/BasicPolymorphicTypeValidator.Builder.html}
[towl] call -> get_javadoc_symbol args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2, link=com/fasterxml/jackson/databind/jsontype/BasicPolymorphicTypeValidator.TypeMatcher.html}
[towl] call -> get_javadoc_symbol args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2, link=com/fasterxml/jackson/databind/jsontype/PolymorphicTypeValidator.Validity.html}
[towl] call <- get_javadoc_symbol
[towl] call -> get_javadoc_symbol args={groupId=com.fasterxml.jackson.core, artifactId=jackson-databind, version=2.22.2, link=com/fasterxml/jackson/databind/jsontype/PolymorphicTypeValidator.html}
[towl] call <- get_javadoc_symbol
[towl] call <- get_javadoc_symbol
[towl] call <- get_javadoc_symbol
[towl] call <- get_javadoc_symbol
[towl] call <- get_javadoc_symbol
[towl] call <- get_javadoc_symbol
[synth] plan status=ok envelope: 2140 chars, diagnostics: [
  {binding=ver, operation=get_latest_version, calls=1},
  {binding=symbols, operation=list_javadoc_symbols, calls=1, streamed=618, filterKept=7, filterDropped=611},
  {binding=classes, elements=7, ok=7},
  {binding=classes.sym, operation=get_javadoc_symbol, calls=7}]
```
