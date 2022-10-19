;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite.doc.md-tag
  (:require [com.viasat.halite.doc.utils :as utils]))

(defn tag-md [{:keys [lang op-maps op-maps-j tag-map tag-map-j]} {:keys [mode prefix]} tag-name tag]
  (->> [(:doc tag) "\n\n"
        (when-let [[link-md] (utils/basic-ref-links lang mode prefix tag "")]
          ["For basic syntax of this data type see: " link-md "\n\n"])
        ["![" (pr-str tag-name) "](" (if (= :user-guide mode)
                                       "images"
                                       "..") "/halite-bnf-diagrams/"
         (utils/url-encode tag-name) (when (= :jadeite lang) "-j") ".svg)\n\n"]
        [(when-let [op-names ((if (= :halite lang) tag-map tag-map-j) (keyword tag-name))]
           (->> op-names
                (map (fn [op-name]
                       (let [op ((if (= :halite lang) op-maps op-maps-j) op-name)]
                         {:op-name op-name
                          :md (str "#### [`" op-name "`](" (utils/get-reference lang mode prefix "full-reference")
                                   "#" (utils/safe-op-anchor op-name) ")" "\n\n"
                                   (if (= :halite lang) (:doc op) (or (:doc-j op) (:doc op)))
                                   "\n\n")})))
                (sort-by :op-name)
                (map :md)))]
        "---\n"]
       flatten (apply str)))

(defn produce-tag-md [{:keys [lang] :as info} {:keys [mode prefix generate-user-guide-hdr-f] :as config} [tag-name tag]]
  (let [tag-name (name tag-name)]
    (->> (tag-md info config tag-name tag)
         (str (when (= :user-guide mode)
                (generate-user-guide-hdr-f (:label tag) (str prefix tag-name "-reference" (utils/get-language-modifier lang)) (str "/" (name lang)) (:doc tag)))
              utils/generated-msg
              "# " (if (= :halite lang) "Halite" "Jadeite")
              " reference: "
              (:label tag)
              "\n\n"))))