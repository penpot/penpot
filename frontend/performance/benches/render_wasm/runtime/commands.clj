;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.runtime.commands)

(defmacro defcommand
  "Defines a command for scenes.

  `(defcommand foo ...)` creates commands `foo` and `foo*`. The latter is
  measured (records raw observations).

  Both variants accept a context or its Promise and return a Promise of the
  updated context. `:requires` declares callable hook names.
  `:measured-variant false` defines a composite that awaits its returned context.
  Argument destructuring and `:pre` checks precede the measured interval in
  every build. The condition map accepts only `:pre`."
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
        command-id        (keyword (str (get-in &env [:ns :name])) (name command))
        starred           (symbol (str command "*"))
        prepare           (gensym "prepare")]
    (when-not (and (simple-symbol? command)
                   (vector? argv)
                   (seq argv)
                   (boolean? measured)
                   (every? #{:requires :measured-variant} (keys options))
                   (every? keyword? (:requires options))
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
                      (fn [] ~@body))]
       (defn ~command
         ~@(when doc [doc])
         [rtx# & args#]
         (invoke {:command ~command-id :measured false :composite ~(not measured)
                  :requires ~(:requires options)}
                 rtx# ~prepare args#))
       ~@(when measured
           [`(defn ~starred
               ~(str "Measured variant of `" command "`." (when doc (str " " doc)))
               [rtx# & args#]
               (invoke {:command ~command-id :measured true :composite false
                        :requires ~(:requires options)}
                       rtx# ~prepare args#))]))))
