;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns hooks.core
  (:require [clj-kondo.hooks-api :as api]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; if-let, when-let
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- use-if-let?
  [form]
  (let [[_ bindings & body] form]
    (and
     ;; Exactly one binding pair.
     (vector? bindings)
     (= 2 (count bindings))

     ;; Exactly one body form.
     (= 1 (count body))

     (let [[binding-sym _] bindings
           if-form         (first body)]
       (and
        ;; Exclude destructuring for v0.
        (symbol? binding-sym)

        ;; Exactly: (if binding-sym then else)
        (seq? if-form)
        (= 4 (count if-form))
        (= 'if (first if-form))
        (= binding-sym (second if-form)))))))


(defn- use-if-let
  "Flags:

  (let [x (foo)]
    (if x
      (bar x)
      (baz)))
  "
  [{:keys [node]}]
  (when (use-if-let? (api/sexpr node))
    (api/reg-finding!
     (assoc (meta (first (:children node)))
            :type :core/use-if-let
            :message "Use if-let instead of let followed by if.")))
  nil)

(defn- use-when-let?
  [form]
  (let [[_ bindings & body] form]
    (and
     ;; Exactly one binding pair.
     (vector? bindings)
     (= 2 (count bindings))

     ;; Exactly one body form.
     (= 1 (count body))

     (let [[binding-sym _] bindings
           when-form       (first body)]
       (and
        ;; Exclude destructuring for v0.
        (symbol? binding-sym)

        ;; (when binding-sym & body) with at least one body form.
        (seq? when-form)
        (<= 3 (count when-form))
        (= 'when (first when-form))
        (= binding-sym (second when-form)))))))

(defn- use-when-let
  "Flags:

  (let [x (foo)]
    (when x
      (bar x)))
  "
  [{:keys [node]}]
  (when (use-when-let? (api/sexpr node))
    (api/reg-finding!
     (assoc (meta (first (:children node)))
            :type :core/use-when-let
            :message "Use when-let instead of let followed by when.")))
  nil)

(defn check-let
  "Runs the let-body checks (if-let, when-let)."
  [{:keys [node] :as m}]
  (use-if-let m)
  (use-when-let m)
  nil)