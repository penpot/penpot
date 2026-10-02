;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.declarations
  "Registration macros for renderer benchmark scenes and cases.

  The macros expand to calls to `register-scene!`/`register-case!` in
  `declarations.cljs`. Registration is done there so it can be inspected and tested.")

(defmacro defscene
  "Registers a scene and returns its id.

   - `id` (keyword)
   - `opts` (`schema:scene`):  maps `:version`, `:description` and
     `:params-schema`.
   - `build` resolves to a one-argument function of the scene parameters."
  [id opts build]
  `(benches.render-wasm.declarations/register-scene!
    (assoc ~opts :id ~id :ns ~(str *ns*) :build ~build)))

(defn- one-argument-fn-form?
  "True for functions of arity 1"
  [form]
  (when (and (seq? form)
             (contains? '#{fn fn* clojure.core/fn cljs.core/fn} (first form)))
    (let [clauses (if (symbol? (second form)) (nnext form) (next form))
          params  (cond
                    (vector? (first clauses))
                    (first clauses)
                    (and (= 1 (count clauses))
                         (seq? (first clauses)))
                    (ffirst clauses))]
      (and (vector? params)
           (= 1 (count params))
           (not= '& (first params))))))

(defn- case-registration-form
  [id scene opts run-fn]
  (let [options (gensym "options")]
    `(let [~options ~opts]
       (when (contains? ~options :run!)
         (throw (ex-info (str "defcase " ~id " requires its run function as the fourth argument")
                         {:type :benches.render-wasm.declarations/invalid-run-function
                          :id ~id})))
       (benches.render-wasm.declarations/register-case!
        (assoc ~options :id ~id :scene ~scene :ns ~(str *ns*)
               ~@(when run-fn [:run! run-fn]))))))

(defmacro defcase
  "Registers a case for `scene` and returns its id.

  An optional inline, one-argument function becomes `:run!`; its argument
  name is chosen by the caller. Other function forms fail at expansion.
  Ticket 14 will call it. Case collection omits it from descriptors. Example:

    (defcase :rects/pan :rects options (fn [context] (pan! context)))"
  ([id scene opts]
   (case-registration-form id scene opts nil))
  ([id scene opts run-fn]
   (when-not (one-argument-fn-form? run-fn)
     (throw (ex-info (str "defcase " id " requires an inline one-argument function")
                     {:type ::invalid-run-function
                      :id id
                      :form run-fn})))
   (case-registration-form id scene opts run-fn)))
