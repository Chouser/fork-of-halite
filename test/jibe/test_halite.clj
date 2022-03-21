;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.test-halite
  (:require [jibe.halite :as halite]
            [clojure.test :refer [deftest is are test-vars]]
            [schema.test :refer [validate-schemas]])
  (:import [clojure.lang ExceptionInfo]))

(clojure.test/use-fixtures :once validate-schemas)

(deftest test-compatible
  (are [t1 t2 expected]
      (= expected (halite/compatible t1 t2))

    :Integer :Integer :Integer
    :Integer :String nil
    :EmptySet :EmptySet :EmptySet
    :EmptySet :EmptyVec nil
    :EmptySet [:Set :Integer] [:Set :Integer]
    [:Set :Integer] :EmptySet [:Set :Integer]
    [:Vec :EmptySet] [:Vec [:Set :String]] [:Vec [:Set :String]]
    [:Vec [:Set :String]] [:Vec [:Set :Integer]] nil
    :EmptyVec [:Vec [:Vec :Integer]] [:Vec [:Vec :Integer]]))

(deftest literal-type-tests
  (let [tenv {:specs {:ws/A$v1 {:x :Integer
                                :y :Boolean
                                :c :ws2/B$v1}
                      :ws2/B$v1 {:s :String}
                      :ws/C$v1 {:xs [:Vec :Integer]}}
              :vars {}
              :refinesTo* {}}]
    (are [expr etype]
        (= etype (halite/type-check tenv expr))

      true :Boolean
      false :Boolean
      1 :Integer
      "hi" :String
      {:$type :ws2/B$v1 :s "foo"} :ws2/B$v1
      {:$type :ws/A$v1 :x 1 :y true
       :c {:$type :ws2/B$v1 :s "bar"}} :ws/A$v1
      #{} :EmptySet
      [] :EmptyVec
      [1 2 3] [:Vec :Integer]
      #{1 2 3} [:Set :Integer]
      [[]] [:Vec :EmptyVec]
      [#{} #{"foo"}] [:Vec [:Set :String]]
      {:$type :ws/C$v1 :xs []} :ws/C$v1
      {:$type :ws/C$v1 :xs [1 2 3]} :ws/C$v1
      [{:$type :ws2/B$v1 :s "bar"}] [:Vec :ws2/B$v1])

    (are [expr err-msg]
        (thrown-with-msg? ExceptionInfo err-msg (halite/type-check tenv expr))

      {} #"instance literal must have :\$type field"
      {:$type "foo"} #"expected namespaced keyword as value of :\$type"
      {:$type :bar} #"expected namespaced keyword as value of :\$type"
      {:$type :foo/bar} #"resource spec not found"
      {:$type :ws/A$v1} #"missing required variables"
      {:$type :ws/A$v1 :x 1 :y 1 :c {:$type :ws2/B$v1 :s "foo"}} #"value of :y has wrong type"
      {:$type :ws/A$v1 :x 1 :y false :c {:$type :ws2/B$v1 :s 12}} #"value of :s has wrong type"
      {:$type :ws2/B$v1 :s "foo" :foo "bar"} #"variables not defined on spec"
      [1 "two"] #"vector elements must be of same type"
      {:$type :ws/C$v1 :xs [1 "two"]} #"vector elements must be of same type")))

(deftest literal-eval-tests
  (let [env {:specs {:ws/A$v1 {:x :Integer
                               :y :Boolean
                               :c :ws2/B$v1}
                     :ws2/B$v1 {:s :String}
                     :ws/C$v1 {:xs [:Vec :Integer]}}
             :vars {}
             :refinesTo {}
             :refinesTo* {}
             :bindings {}}]

    (are [expr]
        (= expr (halite/eval-expr env expr))

      true
      false
      1
      "two"
      {:$type :ws2/B$v1 :s "foo"}
      []
      #{}
      [1 2 3]
      [{:$type :ws2/B$v1 :s "bar"}])))

(deftest application-type-checking-tests
  (let [tenv {:specs {:ws/A$v1 {:x :Integer
                                :y :Boolean
                                :c :ws2/B$v1}
                      :ws2/B$v1 {:s :String}
                      :ws/C$v1 {:xs [:Vec :Integer]}}
              :vars {}
              :refinesTo* {}}]
    (are [expr etype]
        (= etype (halite/type-check tenv expr))

      '(+ 1 2) :Integer
      '(+ (- 3 2) (* 4 5)) :Integer
      '(and (< 1 2) (> (+ 5 6) 90) (or true (<= 1 4))) :Boolean
      '(Cardinality [true true false]) :Integer)

    (are [expr err-msg]
        (thrown-with-msg? ExceptionInfo err-msg (halite/type-check tenv expr))

      '(foo) #"function 'foo' not found"
      '(+ 1 "two") #"no matching signature for '\+'"
      '(+ 1) #"no matching signature for '\+'")))

(deftest application-eval-tests
  (let [tenv {:specs {:ws/A$v1 {:x :Integer
                                :y :Boolean
                                :c :ws2/B$v1}
                      :ws2/B$v1 {:s :String}
                      :ws/C$v1 {:xs [:Vec :Integer]}}
              :vars {}
              :refinesTo* {}}]
    (are [expr v]
        (= v (halite/eval-expr tenv expr))

      '(+ 1 2) 3
      '(- 5 3) 2
      '(< 1 2) true
      '(< 2 1) false
      '(> 1 2) false
      '(> 2 1) true
      '(<= 1 1) true
      '(>= 1 1) true
      '(Cardinality [1 2 3 1]) 4
      '(and true false true) false
      '(or true false false) true
      '(or false false) false
      '(and (<= (+ 3 5) (* 2 2 2)) (or (> 0 1) (<= (Cardinality #{1 2}) 3))) true)))
