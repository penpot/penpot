;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.runtime-unavailable-test
  (:require
   [app.main.data.plugins :as dp]
   [app.main.store :as st]
   [app.util.globals :as ug]
   [app.util.object :as obj]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(def ^:private test-manifest
  {:plugin-id "test-plugin"
   :name "Test plugin"
   :description "A plugin used by the runtime availability tests"
   :host "https://example.com"
   :code ""})

(defn- with-load-plugin
  "Calls dp/open-plugin! with a mocked st/emit! and the given ɵloadPlugin
  value on the global object, returning the emitted events."
  [load-plugin-fn]
  (let [global        ug/global
        had-plugin?   (obj/contains? global "ɵloadPlugin")
        previous      (obj/get global "ɵloadPlugin")
        emitted       (atom [])]
    (if (fn? load-plugin-fn)
      (obj/set! global "ɵloadPlugin" load-plugin-fn)
      (obj/unset! global "ɵloadPlugin"))
    (-> (mock/with-mocks*
          {st/emit! (fn [event & events] (swap! emitted into (cons event events)))}
          (dp/open-plugin! test-manifest nil))
        (p/then (fn [_] @emitted))
        (p/finally (fn []
                     (if had-plugin?
                       (obj/set! global "ɵloadPlugin" previous)
                       (obj/unset! global "ɵloadPlugin")))))))

(t/deftest ^:async load-plugin-notifies-when-runtime-is-unavailable
  (let [emitted (await (with-load-plugin nil))
        types   (mapv ptk/type emitted)]
    (t/is (some #{:app.main.data.notifications/show} types)
          "emits a notification when the plugin runtime is unavailable")
    (t/is (some #{::dp/remove-current-plugin} types)
          "still removes the plugin from the open list")))

(t/deftest ^:async load-plugin-does-not-notify-when-runtime-is-available
  (let [emitted (await (with-load-plugin (fn [_ _] (p/resolved nil))))
        types   (mapv ptk/type emitted)]
    (t/is (not-any? #{:app.main.data.notifications/show} types)
          "does not emit a notification when the plugin runtime is available")))
