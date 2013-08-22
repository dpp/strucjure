(ns strucjure.sugar
  (:require [plumbing.core :refer [fnk for-map aconcat]]
            [strucjure.util :refer [with-syms]]
            [strucjure.pattern :as pattern :refer [->Rest ->Seqable ->Any ->Is ->Guard ->Bind ->Output ->Or ->And ->ZeroOrMore ->WithMeta ->View]]
            [strucjure.graph :as graph]))

;; TODO wrapping parser/rest in [] and calling first (elem) is ugly

(defn with-named-nodes [name->pattern]
  (clojure.core/with-meta
    (for-map [[name pattern] name->pattern] name (->Bind name pattern))
    (meta name->pattern)))

(def sugar-patterns ;D
  (letfn [(view [sym] (->Bind sym (->View sym)))
          (bindable [pattern] (->WithMeta pattern (->Or [{:tag (->Or [(view 'binding) (->Bind 'binding nil)])} (->Bind 'binding nil)])))]
    {'pattern (bindable (->Or [(view 'unquote) (view 'seq) (view 'vec) (view 'map) (view 'any) (view 'default)]))
     'unquote (list `unquote (->Bind 'unquoted (->Any)))
     'seq (list (->Rest (view 'elems)))
     'vec (vector (->Rest (view 'elems)))
     'elems (->ZeroOrMore (->Rest (view 'elem)))
     'elem (->Or [(view 'parser) (view 'rest) (list (view 'pattern))])
     'parser (list (bindable (->Bind 'prefix (->Or ['*]))) (->Rest (view 'elem))) ;; TODO + ?
     'rest (list (bindable '&) (->Rest (view 'elem)))
     'map (->And [{} (->Bind 'elems (->Seqable [(->Rest (->ZeroOrMore [(->Any) (view 'pattern)]))]))]) ;; TODO make s/hashmap for this pattern
     'binding (->Is #(symbol? %))
     'any '_
     'default (->Any)}))

(def prefixes
  {'* 'pattern/->ZeroOrMore})

(def desugar-patterns
  (graph/output-in (with-named-nodes sugar-patterns)
                   'pattern (fnk [binding pattern] (if binding `(->Bind '~binding ~pattern) pattern))
                   'unquote (fnk [unquoted] unquoted)
                   'seq (fnk [seq] `(list ~@seq))
                   'parser (fnk [binding prefix elem]
                                [(let [parser `(~(prefixes prefix) ~(first elem))]
                                   (if binding `(->Bind '~binding ~parser) parser))])
                   'rest (fnk [binding elem]
                              [`(pattern/->Rest ~(if binding `(->Bind '~binding ~(first elem)) (first elem)))])
                   'map (fnk [map] (into {} map))
                   'any (fnk [] `(pattern/->Any))
                   'default (fnk [default] `'~default)))

;; TODO error reporting here
(defn desugar [name sugar]
  (let [desugar-view (graph/graph->view name (graph/trace (eval (graph/patterns->graph desugar-patterns))))]
    (if-let [[output remaining] (desugar-view sugar)]
      (if (nil? remaining)
        output
        (throw (Exception. "Not a pattern")))
      (throw (Exception. "Not a pattern")))))

(defmacro pattern [sugar]
  (desugar 'pattern sugar))

(defmacro view
  ([sugar]
     (pattern/pattern->view (eval (desugar 'pattern sugar))))
  ([sugar input]
     (pattern/*pattern->clj* (eval (desugar 'pattern sugar)) input true {}
                             (fn [output remaining _] [output remaining]))))

(defmacro patterns [& names&sugars]
  (let [name->sugar (for-map [[name sugar] (partition 2 names&sugars)] name sugar)]
    `(let [~@(aconcat (for [[name _] name->sugar] [name `(->Bind '~name (->View '~name))]))]
       ~(for-map [[name sugar] name->sugar] `'~name `(->Bind '~name (pattern ~sugar))))))

(defmacro seqable [& sugars]
  `(->Seqable (pattern ~sugars)))

(defmacro is [& f]
  `(->Is #(~f)))

(defmacro guard [sugar fnk]
  `(->Guard (pattern ~sugar) ~fnk))

(defmacro output [sugar fnk]
  `(->Output (pattern ~sugar) ~fnk))

(defmacro or [& sugars]
  `(->Or [~@(for [sugar sugars] `(pattern ~sugar))]))

(defmacro and [& sugars]
  `(->And [~@(for [sugar sugars] `(pattern ~sugar))]))

(defmacro & [sugar]
  `(->Rest (pattern ~sugar)))

(defmacro * [sugar]
  `(->ZeroOrMore (pattern ~sugar)))

(defmacro with-meta [sugar meta-sugar]
  `(->WithMeta (pattern ~sugar) (pattern ~meta-sugar)))

(comment
  (pattern [1 2 & * 3])
  (pattern [1 2 ^x & * 3])
  (pattern [1 2 & ^x * 3])
  (pattern {:foo 1 :bar (& * 3)})
  (pattern [1 2 ~(or 3 4)])
  (pattern [1 2 ^x ~(->View 'foo)])
  (view [1 2 & * 3] [1 2])
  (view [1 2 & * 3] [1 2 3 3 3])
  (view [1 2 & * 3] [1 2 3 3 3 4])
  (view ~(output [1 2 ^rest & * 3] (fnk [rest] rest)) [1 2 3 3 3])
  (pattern ~(or [(->Bind 'succ (->View 'succ)) (->Bind 'zero (->View 'zero))]))
  (macroexpand-1 '(pattern (1 2 3)))
  (macroexpand-1 '(pattern (succ)))
  (def num-patterns
    (patterns
     num ~(or ~succ ~zero)
     succ (succ ~num)
     zero zero))
  (def num (graph/graph->view 'num (eval (graph/patterns->graph num-patterns))))
  (num 'zero)
  (num '(succ (succ zero)))
  (num '(1 (succ zero)))
  (num '(succ succ))
)
