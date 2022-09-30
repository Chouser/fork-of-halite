;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.propagate
  "Constraint propagation for halite."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [jibe.halite.halite-envs :as halite-envs]
            [jibe.halite.halite-types :as halite-types]
            [jibe.halite.transpile.ssa :as ssa :refer [SpecCtx SpecInfo]]
            [jibe.halite.transpile.lowering :as lowering]
            [jibe.halite.transpile.util :refer [fixpoint mk-junct]]
            [jibe.halite.transpile.simplify :as simplify :refer [simplify-redundant-value! simplify-statically-known-value?]]
            [jibe.halite.transpile.rewriting :as rewriting]
            [loom.graph :as loom-graph]
            [loom.derived :as loom-derived]
            [schema.core :as s]
            [viasat.choco-clj-opt :as choco-clj]))

(declare ConcreteBound)

(s/defschema ConcreteSpecBound
  {:$type (s/cond-pre [(s/one (s/enum :Maybe) :maybe) (s/one halite-types/NamespacedKeyword :type)]
                      halite-types/NamespacedKeyword)
   (s/optional-key :$refines-to) {halite-types/NamespacedKeyword
                                  {halite-types/BareKeyword (s/recursive #'ConcreteBound)}}
   halite-types/BareKeyword (s/recursive #'ConcreteBound)})

(s/defschema AtomBound
  (s/cond-pre
   s/Int
   s/Bool
   (s/enum :Unset)
   {:$in (s/cond-pre
          #{(s/cond-pre s/Int s/Bool (s/enum :Unset))}
          [(s/one s/Int :lower) (s/one s/Int :upper) (s/optional (s/enum :Unset) :Unset)])}))

(s/defschema ConcreteBound
  (s/conditional
   :$type ConcreteSpecBound
   :else AtomBound))

;;;;;;;;; Bound Spec-ification ;;;;;;;;;;;;;;;;

(s/defschema ^:private PrimitiveType (s/enum "Boolean" "Integer"))
(s/defschema ^:private PrimitiveMaybeType [(s/one (s/enum :Maybe) :maybe) (s/one PrimitiveType :inner)])

(s/defschema ^:private FlattenedVar
  [(s/one halite-types/BareKeyword :var-kw)
   (s/one (s/cond-pre PrimitiveMaybeType PrimitiveType) :type)])

(declare FlattenedRefinementMap)

;; A FlattenedVar represents a mapping from a ConcerteSpecBound with vars that are
;; unique within that individual spec instance to choco var names that are
;; unique in the whole composed choco spec, to the depth of composition implied
;; by the given ConcreteSpecBound. FVs form a tree of the same shape as a ConcreteSpecBound,
;; with each ConcreteSpecBound's :$type the same as its corresponding FV's ::spec-id,
;; and every var of that Spec appearing as a key in the FV.
(s/defschema ^:private FlattenedVars
  {::mandatory #{halite-types/BareKeyword}
   ::spec-id halite-types/NamespacedKeyword
   ::refines-to (s/recursive #'FlattenedRefinementMap)
   halite-types/BareKeyword (s/cond-pre FlattenedVar (s/recursive #'FlattenedVars))})

;; A FlattenedRefinement is like a FlattenedVar, but does not support a
;; ::refines-to map because the right place to put such a flattening would be in
;; the FlattenedVar that contains this FlattenedRefinement.
(s/defschema ^:private FlattenedRefinement
  {::mandatory #{halite-types/BareKeyword}
   halite-types/BareKeyword (s/cond-pre FlattenedVar FlattenedVars)})

(s/defschema ^:private FlattenedRefinementMap
  {halite-types/NamespacedKeyword FlattenedRefinement})

(defn- primitive-maybe-type?
  [htype]
  (or (#{:Integer :Boolean} htype)
      (and (halite-types/maybe-type? htype) (vector? htype)
           (#{:Integer :Boolean} (second htype)))))

(defn- spec-maybe-type?
  [htype]
  (or (halite-types/spec-type? htype)
      (halite-types/spec-type? (halite-types/no-maybe htype))))

(defn- unwrap-maybe [htype]
  (cond-> htype
    (and (vector? htype) (= :Maybe (first htype))) second))

(defn refined-var-name [prefix spec-id]
  (str prefix ">" (namespace spec-id) "$" (name spec-id) "|"))

(s/defn ^:private flatten-vars :- FlattenedVars
  ([spec-map :- halite-envs/SpecMap, spec-bound :- ConcreteSpecBound]
   (flatten-vars spec-map [] "" false spec-bound))
  ([spec-map :- halite-envs/SpecMap
    parent-spec-ids :- [halite-types/NamespacedKeyword]
    prefix :- s/Str
    already-optional? :- s/Bool
    spec-bound :- ConcreteSpecBound]
   (let [spec-id (->> spec-bound :$type unwrap-maybe)
         spec-refinements #(-> % spec-map :refines-to)]
     (->
      (reduce
       (fn [vars [var-kw vtype]]
         (let [htype (halite-envs/halite-type-from-var-type spec-map vtype)]
           (cond
             (primitive-maybe-type? htype)
             (let [actually-mandatory? (and already-optional? (not (halite-types/maybe-type? htype)))
                   prefixed-var-kw (keyword (str prefix (name var-kw)))]
               (-> vars
                   (assoc var-kw [prefixed-var-kw
                                  (cond->> vtype
                                    actually-mandatory? (vector :Maybe))])
                   (cond->
                    actually-mandatory? (update ::mandatory conj prefixed-var-kw))))

             (spec-maybe-type? htype)
             (let [spec-id (halite-types/spec-id (unwrap-maybe htype))
                   recur? (or (contains? spec-bound var-kw)
                              (every? #(not= % spec-id) parent-spec-ids))
                   optional? (halite-types/maybe-type? htype)
                   sub-bound (get spec-bound var-kw :Unset)
                   flattened-vars (when recur?
                                    (flatten-vars spec-map
                                                  (conj parent-spec-ids (unwrap-maybe (:$type spec-bound)))
                                                  (str prefix (name var-kw) "|")
                                                  (or already-optional? optional?)
                                                  (if (not= :Unset sub-bound) sub-bound {:$type spec-id})))]
               (cond-> vars
                 recur? (assoc var-kw flattened-vars)

                 (not optional?) (update ::mandatory into (::mandatory flattened-vars))

                 optional? (assoc-in [var-kw :$witness] [(keyword (str prefix (name var-kw) "?")) "Boolean"])))

             :else (throw (ex-info (format "BUG! Variables of type '%s' not supported yet" htype)
                                   {:var-kw var-kw :type htype})))))
       {::mandatory #{} ::spec-id spec-id}
       (->> spec-id spec-map :spec-vars))
      (assoc ::refines-to (->> (tree-seq (constantly true) #(map spec-refinements (keys %)) (spec-refinements spec-id))
                               (apply concat)
                               (into {}
                                     (map (fn [[dest-spec-id _]]
                                            [dest-spec-id (-> (flatten-vars spec-map
                                                                            (conj parent-spec-ids dest-spec-id)
                                                                            (refined-var-name prefix dest-spec-id)
                                                                            (or already-optional? #_optional?)
                                                                            (assoc (get-in spec-bound [:$refines-to dest-spec-id])
                                                                                   :$type dest-spec-id))
                                                              (dissoc ::spec-id ::refines-to))])))))))))

(defn- leaves [m]
  (if (map? m)
    (mapcat leaves (vals m))
    [m]))

(declare lower-spec-bound)

(s/defn ^:private lower-refines-to-bound
  [optional-context? :- s/Bool, refinements :- FlattenedRefinementMap choco-bound refine-to-map]
  (->> refine-to-map
       (reduce (fn [choco-bound [dest-spec-id bound-map]]
                 (merge choco-bound
                        ;; awkwardly use lower-spec-bound for a FlattenedRefinement
                        (lower-spec-bound (assoc (get refinements dest-spec-id)
                                                 ::spec-id dest-spec-id
                                                 ::mandatory #{}
                                                 ::refines-to {})
                                          optional-context?
                                          (assoc bound-map :$type dest-spec-id))))
               choco-bound)))

(s/defn ^:private lower-spec-bound :- choco-clj/VarBounds
  ([vars :- FlattenedVars, spec-bound :- ConcreteSpecBound]
   (lower-spec-bound vars false spec-bound))
  ([vars :- FlattenedVars, optional-context? :- s/Bool, spec-bound :- ConcreteSpecBound]
   (reduce
    (fn [choco-bounds [var-kw bound]]
      (if (= :$refines-to var-kw)
        (lower-refines-to-bound optional-context? (::refines-to vars) choco-bounds bound)
        (let [composite-var? (map? (vars var-kw))
              choco-var (when-not composite-var?
                          (or (some-> var-kw vars first symbol)
                              (throw (ex-info "BUG! No choco var for var in spec bound"
                                              {:vars vars :spec-bound spec-bound :var-kw var-kw}))))
              witness-var (when composite-var?
                            (some-> var-kw vars :$witness first symbol))]
          (cond
            (or (int? bound) (boolean? bound))
            (assoc choco-bounds choco-var (if optional-context? #{bound :Unset} bound))

            (= :Unset bound)
            (if composite-var?
              (if witness-var
                (assoc choco-bounds witness-var false)
                (throw (ex-info (format "Invalid bounds: %s is not an optional variable, and cannot be unset" (name var-kw))
                                {:vars vars :var-kw var-kw :bound bound})))
              (assoc choco-bounds choco-var :Unset))

            (and (map? bound) (contains? bound :$in))
            (let [range-or-set (:$in bound)]
              (when composite-var?
                (throw (ex-info (format "Invalid bound for composite var %s" (name var-kw))
                                {:var-kw var-kw :bound bound})))
              (if (or (vector? range-or-set) (set? range-or-set))
                (assoc choco-bounds choco-var
                       (cond
                         (and (set? range-or-set) optional-context?) (conj range-or-set :Unset)
                         (and (vector range-or-set) (= 2 (count range-or-set)) optional-context?) (conj range-or-set :Unset)
                         :else range-or-set))
                (throw (ex-info (format "Invalid bound for %s" (name var-kw))
                                {:var-kw var-kw :bound bound}))))

            (and (map? bound) (contains? bound :$type))
            (let [optional? (and (vector? (:$type bound)) (= :Maybe (first (:$type bound))))
                  htype (halite-types/concrete-spec-type (cond-> (:$type bound) optional? (second)))]
              (when-not composite-var?
                (throw (ex-info (format "Invalid bound for %s, which is not composite" (name var-kw))
                                {:var-kw var-kw :bound bound})))
              (when (and (nil? witness-var) (halite-types/maybe-type? htype))
                (throw (ex-info (format "Invalid bound for %s, which is not optional" (name var-kw))
                                {:var-kw var-kw :bound bound})))
              (-> choco-bounds
                  (merge (lower-spec-bound (vars var-kw) (or optional-context? optional?) bound))
                  (cond-> (and witness-var (not optional?) (not optional-context?))
                    (assoc witness-var true))))

            :else (throw (ex-info (format "Invalid bound for %s" (name var-kw))
                                  {:var-kw var-kw :bound bound}))))))
    {}
    (dissoc spec-bound :$type))))

(s/defn ^:private optionality-constraint :- s/Any
  [senv :- (s/protocol halite-envs/SpecEnv), flattened-vars :- FlattenedVars]
  (let [witness-var (->> flattened-vars :$witness first symbol)
        mandatory-vars (::mandatory flattened-vars)
        mandatory-clause (when (seq mandatory-vars)
                           (apply list '= witness-var (map #(list 'if-value (symbol %) true false) (sort mandatory-vars))))
        optional-clauses (->> (dissoc flattened-vars ::spec-id)
                              (remove (comp mandatory-vars first second))
                              (filter (fn [[var-kw info]]
                                        (if (vector? info)
                                          (halite-types/maybe-type? (halite-envs/halite-type-from-var-type senv (second info)))
                                          (contains? info :$witness))))
                              (sort-by first)
                              (map (fn [[opt-var-kw info]]
                                     (list '=>
                                           (if (vector? info)
                                             (list 'if-value (symbol (first info)) true false)
                                             (symbol (first (:$witness info))))
                                           witness-var))))]
    (mk-junct 'and (cond->> optional-clauses
                     mandatory-clause (cons mandatory-clause)))))

(s/defn ^:private optionality-constraints :- halite-envs/SpecInfo
  [senv :- (s/protocol halite-envs/SpecEnv), flattened-vars :- FlattenedVars, spec-info :- halite-envs/SpecInfo]
  (->> flattened-vars
       (filter (fn [[var-kw info]] (and (map? info) (contains? info :$witness))))
       (reduce
        (fn [spec-info [var-kw info]]
          (let [cexpr (optionality-constraint senv info)
                spec-info (if (= true cexpr)
                            spec-info
                            (->> [(str "$" (name (first (:$witness info)))) cexpr]
                                 (update spec-info :constraints conj)))]
            (optionality-constraints senv info spec-info)))
        spec-info)))

(s/defn ^:private guard-optional-instance-literal
  [[witness-kw htype] mandatory inst-expr]
  (->> mandatory
       (reduce
        (fn [inst-expr var-kw]
          (list 'if-value (symbol var-kw) inst-expr '$no-value))
        inst-expr)
       (list 'when (symbol witness-kw))))

(s/defn ^:private flattened-vars-as-instance-literal
  [{::keys [mandatory spec-id] :keys [$witness] :as flattened-vars} :- FlattenedVars]
  (cond->>
   (reduce
    (fn [inst-expr [var-kw v]]
      (assoc inst-expr var-kw
             (if (vector? v)
               (symbol (first v))
               (flattened-vars-as-instance-literal v))))
    {:$type spec-id}
    (dissoc flattened-vars ::spec-id ::mandatory ::refines-to :$witness))
    $witness (guard-optional-instance-literal $witness mandatory)))

(s/defn ^:private refinement-equality-constraints :- [halite-envs/NamedConstraint]
  [constraint-name-prefix :- s/Str,
   flattened-vars :- FlattenedVars]
  (concat
   ;; constraints for this composition level
   (let [from-instance (flattened-vars-as-instance-literal flattened-vars)]
     (->>
      (::refines-to flattened-vars)
      (mapcat (fn [[to-spec-id to-vars]] ;; to-var is a FlattenedRefinement
                (let [witness-kw (first (:$witness flattened-vars))
                      to-instance (flattened-vars-as-instance-literal
                                   (conj to-vars
                                         [::spec-id to-spec-id]
                                         [::refines-to {}]
                                         (when-let [w (:$witness flattened-vars)]
                                           [:$witness w])))]
                  (concat
                   ;; instance equality
                   [[(str "$refine" constraint-name-prefix "-to-" to-spec-id)
                     (if-not witness-kw
                       (list '=
                             (list 'refine-to from-instance to-spec-id)
                             to-instance)
                       (list 'let ['$from from-instance]
                             (list 'if-value '$from
                                   (list '=
                                         (list 'refine-to '$from to-spec-id)
                                         to-instance)
                                   true)))]]
                   ;; recurse into spec-types
                   (->> (dissoc to-vars ::mandatory)
                        (mapcat (fn [[var-kw v]]
                                  (when (map? v)
                                    (refinement-equality-constraints
                                     (str constraint-name-prefix "-" to-spec-id "|" var-kw)
                                     v)))))
                   ;; equate inner mandatory provided to outer provided, for tighter bounds
                   (when witness-kw
                     (->> (::mandatory to-vars)
                          (mapcat (fn [flat-kw]
                                    [[(str "$refines" witness-kw "=" flat-kw "?")
                                      (list '=
                                            (symbol witness-kw)
                                            (list 'if-value (symbol flat-kw) true false))]]))))))))))
   ;; recurse into spec-typed vars
   (->> (dissoc flattened-vars ::spec-id ::mandatory ::refines-to)
        (mapcat (fn [[var-kw v]]
                  (when (map? v)
                    (refinement-equality-constraints (str constraint-name-prefix "|" (name var-kw))
                                                     v)))))))

(s/defn ^:private add-refinement-equality-constraints :- halite-envs/SpecInfo
  [flattened-vars :- FlattenedVars
   spec-info :- halite-envs/SpecInfo]
  (update spec-info :constraints into (refinement-equality-constraints "" flattened-vars)))

(defn- spec-ify-bound*
  [flattened-vars specs spec-bound]
  (->>
   {:spec-vars (->> flattened-vars leaves (filter vector?) (into {}))
    :constraints [["vars" (list 'valid? (flattened-vars-as-instance-literal flattened-vars))]]
    :refines-to {}}
   (optionality-constraints specs flattened-vars)
   (add-refinement-equality-constraints flattened-vars)))

(s/defn spec-ify-bound :- halite-envs/SpecInfo
  "Compile the spec-bound into a self-contained halite spec that explicitly states the constraints
  implied by the bound. The resulting spec is self-contained in the sense that:
    * it references no other specs, and
    * it directly incorporates as many spec constraints as possible

  The expressed bound is 'sound', in the sense that every valid instance of the bounded spec
  can be translated into a valid instance of the bound.
  However, the expressed bound is generally not 'tight': there will usually be valid instances of the bound
  that do not correspond to any valid instance of the bounded spec."
  [specs :- halite-envs/SpecMap, spec-bound :- ConcreteSpecBound]
  ;; First, flatten out the variables we'll need.
  (spec-ify-bound* (flatten-vars specs spec-bound) specs spec-bound))

;;;;;;;;;;; Conversion to Choco ;;;;;;;;;

(s/defn ^:private to-choco-type :- choco-clj/ChocoVarType
  [var-type :- halite-envs/VarType]
  (cond
    (or (= [:Maybe "Integer"] var-type) (= "Integer" var-type)) :Int
    (or (= [:Maybe "Boolean"] var-type) (= "Boolean" var-type)) :Bool
    :else (throw (ex-info (format "BUG! Can't convert '%s' to choco var type" var-type) {:var-type var-type}))))

(defn- error->unsatisfiable
  [form]
  (cond
    (seq? form) (if (= 'error (first form))
                  (if (not (string? (second form)))
                    (throw (ex-info "BUG! Expressions other than string literals not currently supported as arguments to error"
                                    {:form form}))
                    (list 'unsatisfiable))
                  (apply list (first form) (map error->unsatisfiable (rest form))))
    (map? form) (update-vals form error->unsatisfiable)
    (vector? form) (mapv error->unsatisfiable form)
    :else form))

(s/defn ^:private to-choco-spec :- choco-clj/ChocoSpec
  "Convert a spec-ified bound to a Choco spec."
  [senv :- (s/protocol halite-envs/SpecEnv), spec-info :- halite-envs/SpecInfo]
  {:vars (-> spec-info :spec-vars (update-keys symbol) (update-vals to-choco-type))
   :optionals (->> spec-info :spec-vars
                   (filter (comp halite-types/maybe-type?
                                 (partial halite-envs/halite-type-from-var-type senv)
                                 val))
                   (map (comp symbol key)) set)
   :constraints (->> spec-info :constraints (map (comp error->unsatisfiable second)) set)})

;;;;;;;;;; Convert choco bounds to spec bounds ;;;;;;;;;

(s/defn ^:private simplify-atom-bound :- AtomBound
  [bound :- AtomBound]
  (if-let [in (:$in bound)]
    (cond
      (and (set? in) (= 1 (count in))) (first in)
      (and (vector? in) (= (first in) (second in))) (first in)
      :else bound)
    bound))

(s/defn ^:private to-atom-bound :- AtomBound
  [choco-bounds :- choco-clj/VarBounds
   var-type :- halite-types/HaliteType
   [var-kw _] :- FlattenedVar]
  (let [bound (-> var-kw symbol choco-bounds)]
    (simplify-atom-bound
     (if-not (coll? bound)
       bound ;; simple value
       {:$in
        (if (halite-types/maybe-type? var-type)
          bound ;; leave any :Unset in the bounds
          (cond ;; remove any :Unset in the bounds
            (vector? bound) (subvec bound 0 2)
            (set? bound) (disj bound :Unset)))}))))

(s/defn ^:private to-spec-bound :- ConcreteSpecBound
  [choco-bounds :- choco-clj/VarBounds
   senv :- (s/protocol halite-envs/SpecEnv)
   flattened-vars  :- FlattenedVars]
  (let [spec-id (::spec-id flattened-vars)
        spec-vars (:spec-vars (halite-envs/lookup-spec senv spec-id))
        spec-type (case (some-> flattened-vars :$witness first symbol choco-bounds)
                    false :Unset
                    (nil true) spec-id
                    [:Maybe spec-id])
        refine-to-pairs (seq (::refines-to flattened-vars))]
    (if (= :Unset spec-type)
      :Unset
      (-> {:$type spec-type}
          (into (->> (dissoc flattened-vars ::mandatory :$witness ::spec-id ::refines-to)
                     (map (fn [[k v]]
                            (let [htype (halite-envs/halite-type-from-var-type
                                         senv (get spec-vars k))]
                              [k (if (spec-maybe-type? htype)
                                   (to-spec-bound choco-bounds senv v)
                                   (to-atom-bound choco-bounds htype v))])))))
          (cond-> refine-to-pairs
            (assoc :$refines-to
                   (->> refine-to-pairs
                        (map (fn [[spec-id flattened-vars]]
                               [spec-id
                                (-> (to-spec-bound choco-bounds
                                                   senv
                                                   (assoc flattened-vars
                                                          ::spec-id spec-id
                                                          ::refines-to {}))
                                    (dissoc :$type))]))
                        (into {}))))))))

;;;;;;;;;;;; Main API ;;;;;;;;;;;;;;;;

(s/defschema Opts
  {:default-int-bounds [(s/one s/Int :lower) (s/one s/Int :upper)]})

(def default-options
  (s/validate
   Opts
   {:default-int-bounds [-1000 1000]}))

(defn- drop-constraints-except-for-Bounds
  [sctx]
  (reduce
   (fn [sctx [spec-id spec-info]]
     (assoc sctx spec-id
            (cond-> spec-info
              (not= :$propagate/Bounds spec-id) (assoc :constraints []))))
   {}
   sctx))

(defn- disallow-optional-refinements
  "Our refinement lowering code is not currently correct when the refinements are optional.
  We'll fix it, but until we do, we should at least not emit incorrect results silently."
  [sctx]
  (doseq [[spec-id {:keys [refines-to ssa-graph]}] sctx
          [to-id {:keys [expr]}] refines-to]
    (let [htype (->> expr (ssa/deref-id ssa-graph) ssa/node-type)]
      (when (halite-types/maybe-type? htype)
        (throw (ex-info (format "BUG! Refinement of %s to %s is optional, and propagate does not yet support optional refinements"
                                spec-id to-id)
                        {:sctx sctx})))))
  sctx)

(s/defn ^:private propagate-concrete :- ConcreteSpecBound
  [spec-map :- halite-envs/SpecMap, opts :- Opts, initial-bound :- ConcreteSpecBound]
  (binding [choco-clj/*default-int-bounds* (:default-int-bounds opts)]
    (let [refinement-graph (loom-graph/digraph (update-vals spec-map (comp keys :refines-to)))
          flattened-vars (flatten-vars spec-map initial-bound)
          lowered-bounds (lower-spec-bound flattened-vars initial-bound)
          spec-ified-bound (spec-ify-bound* flattened-vars spec-map initial-bound)
          initial-sctx (-> spec-map
                           (assoc :$propagate/Bounds spec-ified-bound)
                           (ssa/spec-map-to-ssa))]
      (-> initial-sctx
          (disallow-optional-refinements)
          (lowering/lower-refinement-constraints)
          ;; When is lowered to if once, early, so that rules generally only have one control flow form to worry about.
          ;; Conseqeuntly, no rewrite rules should introduce new when forms!
          (lowering/lower-when)
          (lowering/eliminate-runtime-constraint-violations)
          (lowering/lower-valid?)
          (drop-constraints-except-for-Bounds)

          ;; We may wish to re-enable a weaker version of this rule that
          ;; eliminates error forms in if, but not if-value (which we can't eliminate).
          ;; Experimentation shows that whenever we can eliminate a conditional, we are likely
          ;; to obtain better propagation bounds.
          ;;(lowering/eliminate-error-forms)

          (rewriting/rewrite-reachable-sctx
           [(rewriting/rule simplify/simplify-do)
            (rewriting/rule lowering/bubble-up-do-expr)
            (rewriting/rule lowering/flatten-do-expr)
            (rewriting/rule simplify-redundant-value!)
            (rewriting/rule simplify-statically-known-value?)
            (rewriting/rule lowering/cancel-get-of-instance-literal-expr)
            (rewriting/rule lowering/lower-comparison-exprs-with-incompatible-types)
            (rewriting/rule lowering/lower-instance-comparison-expr)
            (rewriting/rule lowering/push-if-value-into-if-in-expr)
            (rewriting/rule lowering/lower-no-value-comparison-expr)
            (rewriting/rule lowering/lower-maybe-comparison-expr)
            (rewriting/rule lowering/push-gets-into-ifs-expr)
            (rewriting/rule lowering/push-refine-to-into-if)
            (rewriting/rule lowering/push-comparison-into-nonprimitive-if-in-expr)
            (rewriting/rule lowering/eliminate-unused-instance-valued-exprs-in-do-expr)
            (rewriting/rule lowering/eliminate-unused-no-value-exprs-in-do-expr)
            ;; This rule needs access to the refinement graph, so we don't use the rule macro.
            {:rule-name "lower-refine-to"
             :rewrite-fn (partial lowering/lower-refine-to-expr refinement-graph)
             :nodes :all}])
          simplify/simplify
          :$propagate/Bounds
          (ssa/spec-from-ssa)
          (->> (to-choco-spec spec-map))
          (choco-clj/propagate lowered-bounds)
          (to-spec-bound spec-map flattened-vars)))))

(defn- int-bound? [bound]
  (or (int? bound)
      (and (map? bound)
           (contains? bound :$in)
           (every? int? (:$in bound)))))

(defn- union-int-bounds* [a b]
  (cond
    (and (int? a) (int? b)) (if (= a b) a #{a b})
    (int? a) (recur b a)
    (and (set? a) (set? b)) (set/union a b)
    (and (set? a) (int? b)) (conj a b)
    (set? a) (recur b a)
    (and (vector? a) (int? b)) [(min (first a) b) (max (second a) b)]
    (and (vector? a) (set? b)) [(apply min (first a) b) (apply max (second a) b)]
    (vector? a) [(min (first a) (first b)) (max (second a) (second b))]
    :else (throw (ex-info "BUG! Couldn't union int bounds" {:a a :b b}))))

(defn- union-int-bounds [a b]
  (when (not (int-bound? b))
    (throw (ex-info "BUG! Tried to union bounds of different kinds" {:a a :b b})))
  (let [result (union-int-bounds* (cond-> a (:$in a) (:$in)) (cond-> b (:$in b) (:$in)))]
    (if (int? result) result {:$in result})))

(defn- bool-bound? [bound]
  (or (boolean? bound)
      (and (map? bound)
           (contains? bound :$in)
           (every? boolean? (:$in bound)))))

(defn- union-bool-bounds [a b]
  (when (not (bool-bound? b))
    (throw (ex-info "BUG! Tried to union bounds of different kinds" {:a a :b b})))
  (let [a (if (map? a) (:$in a) (set [a]))
        b (if (map? b) (:$in b) (set [b]))
        result (set/union a b)]
    (if (= 1 (count result))
      (first result)
      {:$in result})))

(declare union-concrete-bounds)

(defn- union-refines-to-bounds [a b]
  (reduce
   (fn [result spec-id]
     (assoc result spec-id
            (dissoc
             (union-concrete-bounds
              (assoc (spec-id a) :$type spec-id)
              (assoc (spec-id b) :$type spec-id))
             :$type)))
   {}
   (set (concat (keys a) (keys b)))))

(defn- union-spec-bounds [a b]
  (when (not= (:$type a) (:$type b))
    (throw (ex-info "BUG! Tried to union bounds for different spec types" {:a a :b b})))
  (let [refines-to (union-refines-to-bounds (:$refines-to a) (:$refines-to b))]
    (reduce
     (fn [result var-kw]
       (assoc result var-kw
              (cond
                (and (contains? a var-kw) (contains? b var-kw)) (union-concrete-bounds (var-kw a) (var-kw b))
                (contains? a var-kw) (var-kw a)
                :else (var-kw b))))
     (cond-> {:$type (:$type a)}
       (not (empty? refines-to)) (assoc :$refines-to refines-to))
     (disj (set (concat (keys a) (keys b))) :$type :$refines-to))))

(defn- allows-unset? [bound]
  (or
   (= :Unset bound)
   (and (map? bound) (contains? bound :$in)
        (let [in (:$in bound)]
          (if (set? in)
            (contains? in :Unset)
            (= :Unset (last in)))))
   (and (map? bound) (contains? bound :$type)
        (vector? (:$type bound))
        (= :Maybe (first (:$type bound))))))

(defn- remove-unset [bound]
  (cond
    (= :Unset bound)
    (throw (ex-info "BUG! Called remove-unset on :Unset" {:bound bound}))

    (or (int? bound) (boolean? bound)) bound

    (and (map? bound) (contains? bound :$in))
    (let [in (:$in bound)]
      (cond
        (set? in) (update bound :$in disj :Unset)
        (vector? in) (update bound :$in subvec 0 2)
        :else (throw (ex-info "BUG! Unrecognized bound" {:bound bound}))))

    (and (map? bound) (contains? bound :$type))
    (update bound :$type #(cond-> % (vector? %) second))

    :else (throw (ex-info "BUG! Unrecognized bound" {:bound bound}))))

(defn- add-unset [bound]
  (cond
    (or (boolean? bound) (int? bound)) {:$in #{bound :Unset}}

    (and (map? bound) (contains? bound :$in))
    (let [in (:$in bound)]
      (cond
        (set? in) (update bound :$in conj :Unset)
        (vector? in) (assoc bound :$in (conj (subvec in 0 2) :Unset))
        :else (throw (ex-info "BUG! Unrecognized bound" {:bound bound}))))

    (and (map? bound) (contains? bound :$type))
    (update bound :$type #(cond->> % (keyword? %) (conj [:Maybe])))

    :else (throw (ex-info "BUG! Unrecognized bound" {:bound bound}))))

(s/defn ^:private union-concrete-bounds :- ConcreteBound
  [a :- ConcreteBound, b :- ConcreteBound]
  (if (and (= :Unset a) (= :Unset b))
    :Unset
    (let [unset? (or (allows-unset? a) (allows-unset? b))
          a (if (= :Unset a) b a), b (if (= :Unset b) a b)
          a (remove-unset a),      b (remove-unset b)]
      (cond->
       (cond
         (int-bound? a) (union-int-bounds a b)
         (bool-bound? a) (union-bool-bounds a b)
         :else (union-spec-bounds a b))
        unset? add-unset))))

;;;;;;;;;;;; Abstractness ;;;;;;;;;;;;

;; We handle abstractness by transforming specs and input bounds to eliminate it,
;; and then reversing the transformation on the resulting output bound.
;;
;; For every abstract spec A, let C_0...C_n be a total ordering of all concrete specs
;; that refine to A directly or indirectly.
;;
;; We transform a spec S with variable a of type A into a spec S' with variables:
;;   a$type of type Integer
;;   a$0 of type [:Maybe C_0]
;;   ...
;;   a$n of type [:Maybe C_n]
;;
;; We transform the existing constraint and refinement expressions of S, replacing each
;; occurrence of a with:
;;   (if-value a$0 a$0 (if-value a$1 .... (error "unreachable")))
;;
;; 

(declare Bound)

(s/defschema SpecIdToBound
  {halite-types/NamespacedKeyword
   {halite-types/BareKeyword (s/recursive #'Bound)}})

(s/defschema SpecIdToBoundWithRefinesTo
  {halite-types/NamespacedKeyword
   {(s/optional-key :$refines-to) SpecIdToBound
    halite-types/BareKeyword (s/recursive #'Bound)}
   (s/optional-key :Unset) s/Bool})

(s/defschema AbstractSpecBound
  (s/constrained
   {(s/optional-key :$in) SpecIdToBoundWithRefinesTo
    (s/optional-key :$if) SpecIdToBoundWithRefinesTo
    (s/optional-key :$refines-to) SpecIdToBound}
   #(< (count (select-keys % [:$in :$if])) 2)
   "$in-and-$if-mutually-exclusive"))

(s/defschema ConcreteSpecBound2
  {:$type (s/cond-pre [(s/one (s/enum :Maybe) :maybe) (s/one halite-types/NamespacedKeyword :type)]
                      halite-types/NamespacedKeyword)
   (s/optional-key :$refines-to) SpecIdToBound
   halite-types/BareKeyword (s/recursive #'Bound)})

(s/defschema SpecBound
  (s/conditional
   :$type ConcreteSpecBound2
   :else AbstractSpecBound))

(s/defschema Bound
  (s/conditional
   :$type ConcreteBound
   #(or (not (map? %))
        (and (contains? % :$in)
             (not (map? (:$in %))))) AtomBound
   :else AbstractSpecBound))

(defn- discriminator-var-name [var-kw] (str (name var-kw) "$type"))
(defn- discriminator-var-kw [var-kw] (keyword (discriminator-var-name var-kw)))
(defn- discriminator-var-sym [var-kw] (symbol (discriminator-var-name var-kw)))

(defn- var-entry->spec-id [specs [var-kw var-type]]
  (->> var-type (halite-envs/halite-type-from-var-type specs) halite-types/no-maybe halite-types/spec-id))

(defn- abstract-var?
  [specs var-entry]
  (if-let [spec-id (var-entry->spec-id specs var-entry)]
    (true? (:abstract? (specs spec-id)))
    false))

(defn- lower-abstract-vars
  [specs alternatives {:keys [spec-vars] :as spec-info}]
  (reduce-kv
   (fn [{:keys [spec-vars constraints refines-to] :as spec-info} var-kw var-type]
     (let [alts (alternatives (var-entry->spec-id specs [var-kw var-type]))
           optional-var? (and (vector? var-type) (= :Maybe (first var-type)))
           lowered-expr (reduce
                         (fn [expr i]
                           (let [alt-var (symbol (str (name var-kw) "$" i))]
                             (list 'if-value alt-var alt-var expr)))
                         (if optional-var? '$no-value '(error "unreachable"))
                         (reverse (sort (vals alts))))]
       ;; TODO: Should this just produce an unsatisfiable spec, instead?
       (when (empty? alts)
         (throw (ex-info (format "No values for variable %s: No concrete specs refine to %s" var-kw var-type)
                         {:spec-info spec-info :alternatives alternatives})))
       (assoc
        spec-info

        :spec-vars
        (reduce-kv
         (fn [spec-vars alt-spec-id i]
           (assoc spec-vars (keyword (str (name var-kw) "$" i)) [:Maybe alt-spec-id]))
         (-> spec-vars
             (dissoc var-kw)
             (assoc (discriminator-var-kw var-kw) (cond->> "Integer" optional-var? (vector :Maybe))))
         alts)

        :constraints
        (vec
         (concat
          (map
           (fn [[cname cexpr]]
             [cname (list 'let [(symbol var-kw) lowered-expr] cexpr)])
           constraints)
          (map (fn [i] [(str (name var-kw) "$" i)
                        (list '=
                              (list '= (discriminator-var-sym var-kw) i)
                              (list 'if-value (symbol (str (name var-kw) "$" i)) true false))])
               (sort (vals alts)))))
        :refines-to
        (reduce-kv
         (fn [acc target-spec-id {:keys [expr] :as refn}]
           (assoc acc target-spec-id
                  (assoc refn :expr (list 'let [(symbol var-kw) lowered-expr] expr))))
         {}
         refines-to))))
   spec-info
   (filter (partial abstract-var? specs) spec-vars)))

(defn- invert-adj [adj-lists]
  (reduce-kv
   (fn [acc from to-list]
     (reduce
      (fn [acc to]
        (if (contains? acc to)
          (update acc to conj from)
          (assoc acc to [from])))
      acc
      to-list))
   {}
   adj-lists))

(declare lower-abstract-bounds)

(defn- lower-abstract-var-bound
  [specs alternatives var-kw optional-var? alts-for-spec {:keys [$if $in $type $refines-to] :as abstract-bound} parent-bound]
  (cond
    $if (let [b {:$in (merge (zipmap (keys alts-for-spec) (repeat {})) $if)}
              b (cond-> b $refines-to (assoc :$refines-to $refines-to))
              b (cond-> b (and optional-var? (not (false? (:Unset $if)))) (assoc-in [:$in :Unset] true))]
          (recur specs alternatives var-kw optional-var? alts-for-spec b parent-bound))
    $type (let [b {:$in {$type (dissoc abstract-bound :$type)}}]
            (recur specs alternatives var-kw optional-var? alts-for-spec b parent-bound))
    $in (let [unset? (true? (:Unset $in))
              alt-ids (->> (dissoc $in :Unset) keys (map alts-for-spec) set)]
          (reduce-kv
           (fn [parent-bound spec-id spec-bound]
             (let [i (alts-for-spec spec-id)]
               (assoc parent-bound (keyword (str (name var-kw) "$" i))
                      (lower-abstract-bounds
                       (cond-> (assoc spec-bound :$type [:Maybe spec-id])
                         $refines-to (assoc :$refines-to $refines-to))
                       specs alternatives))))
           ;; restrict the discriminator
           (assoc parent-bound (discriminator-var-kw var-kw) {:$in (cond-> alt-ids unset? (conj :Unset))})
           (dissoc $in :Unset)))
    :else (throw (ex-info "Invalid abstract bound" {:bound abstract-bound}))))

(s/defn ^:private lower-abstract-bounds :- ConcreteSpecBound2
  [spec-bound :- SpecBound, specs :- halite-envs/SpecMap, alternatives]
  (let [spec-id (:$type spec-bound)
        spec-id (cond-> spec-id (vector? spec-id) second) ; unwrap [:Maybe ..]
        {:keys [spec-vars] :as spec} (specs spec-id)]
    (->>
     spec-vars
     (filter #(abstract-var? specs %))
     (reduce
      (fn [spec-bound [var-kw var-type :as var-entry]]
        (let [var-spec-id (var-entry->spec-id specs var-entry)
              optional-var? (and (vector? var-type) (= :Maybe (first var-type)))
              alts (alternatives var-spec-id)
              var-bound (or (var-kw spec-bound) {:$if {}})]
          (if (= :Unset var-bound)
            (-> spec-bound (dissoc var-kw) (assoc (discriminator-var-kw var-kw) :Unset))
            (let [var-bound (cond-> var-bound
                              ;; TODO: intersect $refines-to bounds when present at both levels
                              (= [:$refines-to] (keys var-bound)) (assoc :$if {}))]
              (-> spec-bound
                  ;; remove the abstract bound, if any
                  (dissoc var-kw)
                  ;; add in a bound for the discriminator
                  (assoc (discriminator-var-kw var-kw) {:$in (cond-> (set (range (count alts))) optional-var? (conj :Unset))})
                  ;; if an abstract bound was provided, lower it
                  (cond->> var-bound (lower-abstract-var-bound specs alternatives var-kw optional-var? alts var-bound)))))))
      spec-bound))))

(declare raise-abstract-bounds)

(defn- raise-abstract-var-bound
  [specs alternatives var-kw alts parent-bound]
  (let [discrim-kw (discriminator-var-kw var-kw)
        parent-bound (reduce
                      (fn [parent-bound [spec-id i]]
                        (let [alt-var-kw (keyword (str (name var-kw) "$" i))
                              alt-bound (parent-bound alt-var-kw)]
                          (cond-> (dissoc parent-bound alt-var-kw)
                            (not= :Unset alt-bound)
                            (-> (assoc-in [var-kw :$in spec-id] (-> alt-bound (raise-abstract-bounds specs alternatives) (dissoc :$type)))
                                (update-in [var-kw :$refines-to] union-refines-to-bounds (:$refines-to alt-bound))))))
                      (-> parent-bound
                          (assoc var-kw (if (= :Unset (discrim-kw parent-bound))
                                          :Unset
                                          {:$in (cond-> {} (some-> parent-bound discrim-kw :$in :Unset) (assoc :Unset true))}))
                          (dissoc discrim-kw))
                      alts)
        alt-bounds (get-in parent-bound [var-kw :$in])]
    (if (= 1 (count alt-bounds))
      (let [[spec-id bound] (first alt-bounds)]
        (assoc parent-bound var-kw (assoc bound :$type spec-id)))
      parent-bound)))

(s/defn ^:private raise-abstract-bounds :- SpecBound
  [spec-bound :- ConcreteSpecBound2, specs :- halite-envs/SpecMap, alternatives]
  (let [spec-id (:$type spec-bound)
        spec-id (cond-> spec-id (vector? spec-id) second)
        {:keys [spec-vars] :as spec} (specs spec-id)]
    (->>
     spec-vars
     (filter #(abstract-var? specs %))
     (reduce
      (fn [spec-bound [var-kw var-type :as var-entry]]
        (let [var-spec-id (var-entry->spec-id specs var-entry)
              alts (alternatives var-spec-id)]
          (raise-abstract-var-bound specs alternatives var-kw alts spec-bound)))
      spec-bound))))

(s/defn propagate :- SpecBound
  ([senv :- (s/protocol halite-envs/SpecEnv), initial-bound :- SpecBound]
   (propagate senv default-options initial-bound))
  ([senv :- (s/protocol halite-envs/SpecEnv), opts :- Opts, initial-bound :- SpecBound]
   (let [specs (cond-> senv
                 (or (instance? jibe.halite.halite_envs.SpecEnvImpl senv)
                     (not (map? senv))) (halite-envs/build-spec-map (:$type initial-bound)))
         abstract? #(-> % specs :abstract? true?)
         refns (invert-adj (update-vals specs (comp keys :refines-to)))
         refn-graph (if (empty? refns)
                      (loom-graph/digraph)
                      (loom-graph/digraph refns))
         alternatives (reduce
                       (fn [alts spec-id]
                         (->> spec-id
                              (loom-derived/subgraph-reachable-from refn-graph)
                              loom-graph/nodes
                              (filter (complement abstract?))
                              sort
                              (#(zipmap % (range)))
                              (assoc alts spec-id)))
                       {}
                       (filter abstract? (keys specs)))]
     (-> specs
         (update-vals #(lower-abstract-vars specs alternatives %))
         (propagate-concrete opts (lower-abstract-bounds initial-bound specs alternatives))
         (raise-abstract-bounds specs alternatives)))))
