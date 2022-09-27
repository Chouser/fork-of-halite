;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.doc.md-how-to
  (:require [jibe.halite.doc.utils :as utils]
            [jibe.halite-guide :as halite-guide]
            [jibe.logic.jadeite :as jadeite]
            [clojure.string :as string])
  (:import [jibe.halite_guide HCInfo]))

(defn how-to-md [lang [id how-to]]
  (->> [utils/generated-msg
        "## " (:label how-to) "\n\n"
        (:desc how-to) "\n\n"
        (loop [[c & more-c] (:contents how-to)
               spec-map nil
               results []]
          (if c
            (cond
              (string? c) (recur more-c spec-map (conj results (str c "\n\n")))

              (and (map c) (:spec-map c)) (recur more-c (:spec-map c) (conj results (utils/code-snippet lang (utils/spec-map-str lang (:spec-map c)))))
              (and (map c) (:code c)) (let [h-expr (:code c)
                                            ^HCInfo i (halite-guide/hc-body
                                                       spec-map
                                                       h-expr)
                                            {:keys [t h-result j-result j-expr]} {:t (.-t i)
                                                                                  :h-result (.-h-result i)
                                                                                  :j-result (.-j-result i)
                                                                                  :j-expr (jadeite/to-jadeite h-expr)}
                                            skip-lint? (get c :skip-lint? false)
                                            [h-result j-result] (if (and (not skip-lint?)
                                                                         (vector? t)
                                                                         (= :throws (first t)))
                                                                  [t t]
                                                                  [h-result j-result])]
                                        (when (and (not (:throws c))
                                                   (vector? h-result)
                                                   (= :throws (first h-result)))
                                          (throw (ex-info "failed" {:h-expr h-expr
                                                                    :h-result h-result})))
                                        (when (and (:throws c)
                                                   (not (and (vector? h-result)
                                                             (= :throws (first h-result)))))
                                          (throw (ex-info "expected to fail" {:h-expr h-expr
                                                                              :h-result h-result})))
                                        (recur more-c spec-map
                                               (conj results (utils/code-snippet lang (str ({:halite (utils/pprint-halite h-expr)
                                                                                             :jadeite (str j-expr "\n")} lang)
                                                                                           (when (or (:result c)
                                                                                                     (:throws c))
                                                                                             (str ({:halite  "\n\n;-- result --\n"
                                                                                                    :jadeite "\n\n//-- result --\n"}
                                                                                                   lang)
                                                                                                  ({:halite (utils/pprint-halite h-result)
                                                                                                    :jadeite (str j-result "\n")} lang)))))))))
            results))
        (when-let [basic-refs (some-> (if (= :halite lang)
                                        (:basic-ref how-to)
                                        (or (:basic-ref-j how-to)
                                            (:basic-ref how-to)))
                                      sort)]
          ["#### Basic elements:\n\n"
           (string/join ", "
                        (for [basic-ref basic-refs]
                          (str "[`" basic-ref "`]"
                               "("
                               (if (= :halite lang)
                                 "../halite-basic-syntax-reference.md"
                                 "../jadeite-basic-syntax-reference.md")
                               "#" basic-ref
                               ")")))
           "\n\n"])

        (when-let [op-refs (some->> (:op-ref how-to)
                                    (map ({:halite identity
                                           :jadeite utils/translate-op-name-to-jadeite} lang)))]
          ["#### Operator reference:\n\n"
           (for [a (sort op-refs)]
             (str "* " "[`" a "`](" (if (= :halite lang)
                                      "../halite-full-reference.md"
                                      "../jadeite-full-reference.md")
                  "#" (utils/safe-op-anchor a) ")" "\n"))
           "\n\n"])
        (when-let [how-to-refs (:how-to-ref how-to)]
          ["#### How tos:\n\n"
           (for [a (sort how-to-refs)]
             (str "* " "[" (name a) "](" (name a) ".md" ")" "\n"))
           "\n\n"])]
       flatten
       (apply str)))
