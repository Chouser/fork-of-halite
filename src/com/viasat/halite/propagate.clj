;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite.propagate
  "Constraint propagation for halite."
  (:require [com.viasat.halite.envs :as halite-envs]
            [com.viasat.halite.propagate.prop-abstract :as prop-abstract]
            [com.viasat.halite.transpile.ssa :as ssa]
            [schema.core :as s]))

(def Bound prop-abstract/Bound)

(def SpecBound prop-abstract/SpecBound)

(def Opts prop-abstract/Opts)

(def default-options prop-abstract/default-options)

(s/defn propagate :- SpecBound
  ([senv :- (s/protocol halite-envs/SpecEnv), initial-bound :- SpecBound]
   (propagate senv default-options initial-bound))
  ([senv :- (s/protocol halite-envs/SpecEnv), opts :- prop-abstract/Opts, initial-bound :- SpecBound]
   (let [sctx (if (map? senv)
                (ssa/spec-map-to-ssa senv)
                (ssa/build-spec-ctx senv (:$type initial-bound)))]
     (prop-abstract/propagate sctx opts initial-bound))))
