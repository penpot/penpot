;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.declarations
  "Registration macros for renderer benchmark scenes and cases.

  The macros expand to calls to `register-scene!`/`register-case!` in
  `declarations.cljs`. Registration is done there so it can be inspected and tested.")

(defn- single-arg-vector?
  "True when `form` is a vector holding one plain symbol."
  [form]
  (and (vector? form)
       (= 1 (count form))
       (symbol? (first form))
       (not= '& (first form))))

(defn- check-arg-vector!
  [macro id argv]
  (when-not (single-arg-vector? argv)
    (throw (ex-info (str macro " " id " requires one argument vector with one symbol")
                    {:type ::invalid-run-function
                     :id id
                     :form argv}))))

(defmacro defscene
  "Registers a scene and returns its id.

   - `id` (keyword)
   - `opts` (`schema:scene`):  maps `:version`, `:description` and
     `:params-schema`.
   - `argv` names one scene parameter symbol
   - `body` builds the snapshot.

  Example:

    (defscene :rects opts [params] (scene params))"
  [id opts argv & body]
  (check-arg-vector! "defscene" id argv)
  `(benches.render-wasm.declarations/register-scene!
    (assoc ~opts :id ~id :ns ~(str *ns*) :build (fn ~argv ~@body))))

(defn- case-registration-form
  [id scene opts run-fn]
  (let [options (gensym "options")]
    `(let [~options ~opts]
       (when (contains? ~options :run!)
         (throw (ex-info (str "defcase " ~id " requires its run function as the fourth argument")
                         {:type :benches.render-wasm.declarations/invalid-run-function
                          :id ~id})))
       (when (nil? ~scene)
         (throw (ex-info (str "defcase " ~id " requires a :scene/case id")
                         {:type :benches.render-wasm.declarations/invalid-run-function
                          :id ~id
                          :form ~id})))
       (benches.render-wasm.declarations/register-case!
        (assoc ~options :id ~id :scene ~scene :ns ~(str *ns*)
               ~@(when run-fn [:run! run-fn]))))))

(defn- scene-from-id
  "Scene keyword derived from a `:scene/case` id. `nil` when `id` carries
  no scene namespace."
  [id]
  (when-let [scene-ns (namespace id)]
    (keyword scene-ns)))

(defmacro defcase
  "Registers a case for `scene` and returns its id.

  The scene comes from the namespace part of the `:scene/case` id. An
  optional argument vector plus body becomes `:run!`; its argument name
  is chosen by the caller. The no-body arity registers a case without `:run!`.
  Ticket 14 will call it. Case collection omits it from descriptors. Example:

    (defcase :rects/pan options [context] (pan! context))

  "
  ([id opts]
   (case-registration-form id (scene-from-id id) opts nil))
  ([id opts argv & body]
   (check-arg-vector! "defcase" id argv)
   (case-registration-form id (scene-from-id id) opts `(fn ~argv ~@body))))
