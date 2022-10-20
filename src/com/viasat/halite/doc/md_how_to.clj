;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite.doc.md-how-to
  (:require [com.viasat.halite.doc.utils :as utils]
            [com.viasat.halite.doc.run :as halite-run]
            [com.viasat.jadeite :as jadeite]
            [clojure.string :as string])
  (:import [com.viasat.halite.doc.run HCInfo]))

(defn how-to-contents [lang mode translate-spec-map-f how-to]
  (loop [[c & more-c] (:contents how-to)
         spec-map nil
         spec-map-throws nil
         results []]
    (if c
      (cond
        (string? c) (recur more-c spec-map spec-map-throws (conj results (if (= mode :specs-only)
                                                                           nil
                                                                           (str c "\n\n"))))

        (and (map c) (:spec-map c)) (let [spec-map-result (when (= :auto (:throws c))
                                                            (let [^HCInfo i (binding [halite-run/*check-spec-map-for-cycles?* true]
                                                                              (halite-run/hc-body
                                                                               (:spec-map c)
                                                                               'true))
                                                                  h-result (.-h-result i)]
                                                              (when (not (and (vector? h-result)
                                                                              (= :throws (first h-result))))
                                                                (throw (ex-info "expected spec-map to fail" {:spec-map spec-map
                                                                                                             :h-result h-result})))
                                                              (str ({:halite  "\n\n;-- result --\n"
                                                                     :jadeite "\n\n//-- result --\n"}
                                                                    lang)
                                                                   ({:halite (utils/pprint-halite h-result)
                                                                     :jadeite (str h-result "\n")} lang))))]
                                      (recur more-c
                                             (:spec-map c)
                                             (:throws c)
                                             (conj results
                                                   (if (= :specs-only mode)
                                                     (translate-spec-map-f (:spec-map c))
                                                     (utils/code-snippet lang mode (str (utils/spec-map-str lang (:spec-map c))
                                                                                        spec-map-result))))))
        (and (map c) (:code c)) (let [h-expr (:code c)
                                      ^HCInfo i (halite-run/hc-body
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
                                  (recur more-c
                                         spec-map
                                         spec-map-throws
                                         (conj results (if (= mode :specs-only)
                                                         nil
                                                         (utils/code-snippet
                                                          lang mode
                                                          (str ({:halite (utils/pprint-halite h-expr)
                                                                 :jadeite (str j-expr "\n")} lang)
                                                               (when (or (:result c)
                                                                         (:throws c))
                                                                 (str ({:halite  "\n\n;-- result --\n"
                                                                        :jadeite "\n\n//-- result --\n"}
                                                                       lang)
                                                                      ({:halite (utils/pprint-halite h-result)
                                                                        :jadeite (str j-result "\n")} lang))))))))))
      results)))

(defn how-to-md [lang {:keys [mode prefix append-sidebar-l3-f generate-how-to-user-guide-hdr-f translate-spec-map-f]} *sidebar-atom* [id how-to doc-type]]
  (->> [(when (= :user-guide mode)
          (generate-how-to-user-guide-hdr-f lang prefix id how-to))
        utils/generated-msg
        "## " (:label how-to) "\n\n"
        (:desc how-to) "\n\n"
        (how-to-contents lang mode translate-spec-map-f how-to)
        (let [basic-ref-links (utils/basic-ref-links lang mode prefix how-to (if (= :user-guide mode)
                                                                               nil
                                                                               "../"))
              op-refs (some->> (:op-ref how-to)
                               (map ({:halite identity
                                      :jadeite utils/translate-op-name-to-jadeite} lang)))
              how-to-refs (:how-to-ref how-to)
              tutorial-refs (:tutorial-ref how-to)
              explanation-refs (:explanation-ref how-to)]
          (when (= :user-guide mode)
            (append-sidebar-l3-f *sidebar-atom* lang mode prefix [lang doc-type] (:label how-to) id))
          [(when (or basic-ref-links op-refs how-to-refs tutorial-refs explanation-refs)
             "### Reference\n\n")
           (when basic-ref-links
             ["#### Basic elements:\n\n"
              (interpose ", " basic-ref-links) "\n\n"])
           (when op-refs
             ["#### Operator reference:\n\n"
              (for [a (sort op-refs)]
                (str "* " "[`" a "`](" (when (= :local mode) "../") (utils/get-reference-filename-link lang mode prefix "full")
                     "#" (utils/safe-op-anchor a) ")" "\n"))
              "\n\n"])
           (when how-to-refs
             ["#### How Tos:\n\n"
              (for [a (sort how-to-refs)]
                (str "* " "[" (name a) "](" (when (= :local mode) "../how-to/") (utils/get-reference lang mode prefix (name a)) ")" "\n"))
              "\n\n"])
           (when tutorial-refs
             ["#### Tutorials:\n\n"
              (for [a (sort tutorial-refs)]
                (str "* " "[" (name a) "](" (when (= :local mode) "../tutorial/") (utils/get-reference lang mode prefix (name a)) ")" "\n"))
              "\n\n"])
           (when explanation-refs
             ["#### Explanations:\n\n"
              (for [a (sort explanation-refs)]
                (str "* " "[" (name a) "](" (when (= :local mode) "../explanation/") (utils/get-reference lang mode prefix (name a)) ")" "\n"))
              "\n\n"])])]
       flatten
       (apply str)))
