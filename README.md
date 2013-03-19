# Antlers

A swift, robust templating system for clojure

## Introduction

Antlers grew out of a plain mustache library called [Stencil](http://github.com/davidsantiago/stencil), but under the strain of heavy use acquired many features that now no longer align with the original mustache philosophy (in particular, lambdas no longer behave like mustache lambdas, though there are many new features as well).  Consider it a superset of mustache, with lambdas that make sense.  

## Usage

To simply render a string as a template, use `render-string`:

    (require '[antlers.core :as antlers])

    (antlers/render-string 
     "Roaming the open {{land}}"
     {:land "tundra!"})

    --> "Roaming the open tundra!"

To render the same template over and over again, use render-file (which caches the parsed AST that can subsequently be reused repeatedly):

    (antlers/render-file
     "roaming"
     {:land "taiga!"})

    --> "Roaming the open taiga!"

This will find a file called "roaming" anywhere in your classpath.  

To cache the AST for a string, you can register a template with a key and then call `render-file` using that key.  It will behave as if it was loaded from a file:

    (use 'antlers.parser)
    (register-template "roaming" "Roaming the open {{land}}")
    (antlers/render-file "roaming" {:land "tundra!"})

    --> "Roaming the open tundra!"

## Blocks

Antlers supports content blocks, which are useful for things like layouts.  Suppose you have a file "layout" which looks something like this:

    HEADER
      BODY AAAA
    FOOTER

But you would like to have other bodies, like `BODY BBBB`, without replicating `HEADER` and `FOOTER` over and over again.  The trick is to use a block:

    HEADER
      {{%body}}{{/body}}
    FOOTER

Then, in another file "body_a" can be the content:

    {{< layout}}
    {{%body}}BODY AAAA{{/body}}

Which, when called with `(antlers/render-file "body_a" {})` yields:

    HEADER
      BODY AAAA
    FOOTER

Now, you can have another file called "body_b" which can have totally different contents for the `body` block.  You only need to specify the changes in the blocks, not the rest of the file:

    {{< layout}}
    {{%body}}This is the more conversational body for the B template{{/body}}

Which yields when rendering "body_b":

    HEADER
      This is the more conversational body for the B template
    FOOTER

## Loop variables

## Helper functions

## License

Eclipse Public License
