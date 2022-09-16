;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.doc.md-outline
  (:require [jibe.halite.doc.utils :as utils]))

(defn produce-outline [{:keys [how-tos tag-def-map how-to-filename tag-md-filename]}]
  (->>
   [utils/generated-msg
    "# Halite resource specifications\n
All features are available in both Halite (s-expression) syntax and Jadeite (C-like) syntax.\n\n"

    "## Tutorials\n\nTBD\n\n"

    "## How-To Guides\n\n"
    (->> how-tos
         (group-by (comp namespace key))
         (mapcat (fn [[namespace how-tos]]
                   (str "### " namespace "\n\n"
                        (->> how-tos
                             (sort-by (comp :label val))
                             (mapcat (fn [[id h]] ["* " (:label h)
                                                   " [(Halite)](" (how-to-filename :halite id) ")"
                                                   " [(Jadeite)](" (how-to-filename :jadeite id) ")\n"
                                                   "  * " (:desc h) "\n"]))
                             (apply str))
                        "\n"))))

    "## Explanation\n\nTBD\n\n"

    "## Reference\n

* Basic Syntax [(Halite)](halite-basic-syntax-reference.md)         [(Jadeite)](jadeite-basic-syntax-reference.md)
* All Operators (alphabetical) [(Halite)](halite-full-reference.md) [(Jadeite)](jadeite-full-reference.md)
* Error ID Reference [(Halite)](halite-err-id-reference.md)         [(Jadeite)](jadeite-err-id-reference.md)

#### Operators grouped by tag:\n\n"

    (let [separate-tags ['control-flow 'special-form]
          cols (->> tag-def-map vals (map :type-mode) set sort
                    (remove (set separate-tags)))]
      [(->> separate-tags
            (map (fn [tag]
                   ["* " (get-in tag-def-map [(keyword tag) :label])
                    " [(Halite)]("  (tag-md-filename :halite  tag) ")"
                    " [(Jadeite)](" (tag-md-filename :jadeite tag) ")\n"])))
       "<table>"
       "<tr><th></th>"
       (->> cols (map (fn [tm] ["<th>" tm "</th>\n"])))
       "</tr>"
       (->> tag-def-map vals (map :type) set sort
            (remove nil?)
            (map (fn [t]
                   ["<tr>"
                    "<th>" t "</th>"
                    (->> cols
                         (map (fn [tm]
                                ["<td>\n\n"
                                 (->> tag-def-map
                                      (filter #(= tm (:type-mode (val %))))
                                      (filter #(= t (:type (val %))))
                                      (map (fn [[k v]]
                                             [" [H](" (tag-md-filename :halite  (name k)) ")"
                                              " [J](" (tag-md-filename :jadeite (name k)) ")\n"])))
                                 "</td>"])))
                    "</tr>"])))
       "</table>\n\n"])]
   flatten
   (apply str)))