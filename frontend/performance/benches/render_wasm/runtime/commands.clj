;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.commands
  "Declarations of renderer commands.
  A command transforms a command context, a map of case data, a scene snapshot,
  the current camera view and hooks. A scene is a recipe for shapes; a case
  pairs a scene with its work. A snapshot contains concrete shapes and their
  references. The view stores zoom scale and camera offsets. Hooks are functions
  for clocks, scheduling and renderer operations. A measured command records
  an event containing raw numeric values for named quantities, called metrics.")

(defmacro defcommand
  "Defines a base command and its measured function with a `*` suffix.

  Both accept a command context or its Promise and return a Promise of the next
  context. A synchronous body returns that context. The measured function
  requires an active command execution and records one event with
  `:command/elapsed`, the body duration in milliseconds.

  Options:
  - `:measured-variant false` defines a composite's base function. A composite
    combines commands and records events from the measured commands it calls.
  - `:async [complete fail]` lets the body complete asynchronously. Successful
    completion requires `complete`; `fail` reports a cause. The first call to
    either callback determines the outcome. A returned Promise forwards its
    rejection to `fail`.

  `complete` accepts the next context and an optional map. `:end-ms` supplies
  the clock reading at the end of the work. When the map omits `:end-ms`,
  `complete` captures a reading on entry. `:metrics` maps namespaced metric
  keywords to nonempty vectors of raw numbers.
  `:trace` contains renderer details such as individual render calls and waits.

  Argument destructuring and `:pre` conditions precede work and timing in every
  build. A failed condition rejects the returned Promise. The condition map
  accepts `:pre`. Output validation and event recording follow the endpoint."
  [command & forms]
  (let [[doc forms]       (if (string? (first forms))
                            [(first forms) (next forms)]
                            [nil forms])
        [options forms]   (if (map? (first forms))
                            [(first forms) (next forms)]
                            [{} forms])
        [argv & body]     forms
        [conditions body] (if (and (map? (first body)) (next body))
                            [(first body) (next body)]
                            [nil body])
        measured          (get options :measured-variant true)
        handlers          (:async options)
        command-id        (keyword (str (get-in &env [:ns :name])) (name command))
        starred           (symbol (str command "*"))
        prepare           (gensym "prepare")]
    (when-not (and (simple-symbol? command)
                   (vector? argv)
                   (seq argv)
                   (boolean? measured)
                   (every? #{:measured-variant :async} (keys options))
                   (or (not (contains? options :async))
                       (and (vector? handlers)
                            (= 2 (count handlers))
                            (every? simple-symbol? handlers)
                            (apply distinct? handlers)))
                   (every? #{:pre} (keys conditions))
                   (or (nil? (:pre conditions)) (vector? (:pre conditions))))
      (throw (ex-info "invalid command declaration" {:command command})))
    `(let [~prepare (fn ~argv
                      ~@(for [condition (:pre conditions)]
                          `(when-not ~condition
                             (throw (ex-info "command precondition failed"
                                             {:type ::invalid-arguments
                                              :command ~command-id
                                              :condition '~condition}))))
                      (fn ~(or handlers []) ~@body))]
       (defn ~command
         ~@(when doc [doc])
         [rtx# & args#]
         (invoke {:command ~command-id :measured false :composite ~(not measured)
                  :async ~(some? handlers)}
                 rtx# ~prepare args#))
       ~@(when measured
           [`(defn ~starred
               ~(str "Measured variant of `" command "`." (when doc (str " " doc)))
               [rtx# & args#]
               (invoke {:command ~command-id :measured true :composite false
                        :async ~(some? handlers)}
                       rtx# ~prepare args#))]))))
