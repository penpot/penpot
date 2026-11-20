;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-libraries-load-test
  "Tests for a library of the open file that fails to load."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(defn- error-of-type
  [type]
  (ex-info "http error" {:type type}))

(t/deftest broken-library-error-only-for-backend-errors
  ;; the backend failed to load the library file
  (t/is (true? (dwl/broken-library-error? (error-of-type :server-error))))
  ;; transient or transport errors never disconnect a library
  (doseq [type [:network :offline :bad-gateway :service-unavailable
                :gateway-error :rate-limit nil]]
    (t/is (false? (dwl/broken-library-error? (error-of-type type)))
          (str "type " type)))
  (t/is (false? (dwl/broken-library-error? (js/Error. "boom")))))

(defn- watch-events
  "Synchronously collects the events the watch of `event` emits."
  [event state]
  (let [events (atom [])]
    (->> (ptk/watch event state (rx/empty))
         (rx/subs! #(swap! events conj %)))
    @events))

(t/deftest library-load-failed-disconnects-a-direct-library
  (let [file-id    (uuid/next)
        library-id (uuid/next)
        library    {:id library-id :name "Broken" :is-indirect false}
        events     (watch-events (dwl/library-load-failed file-id library (error-of-type :server-error))
                                 {})]
    ;; the library is unlinked from the file and the user is told
    (t/is (= [::dwl/detach-library :app.main.data.notifications/show]
             (mapv ptk/type events)))))

(t/deftest library-load-failed-drops-an-indirect-library
  (let [file-id    (uuid/next)
        library-id (uuid/next)
        other-id   (uuid/next)
        library    {:id library-id :name "Broken" :is-indirect true}
        state      {:files {library-id {:id library-id}
                            other-id   {:id other-id}}}
        events     (watch-events (dwl/library-load-failed file-id library (error-of-type :server-error))
                                 state)]
    ;; an indirect library is not linked to the file: only drop it from the state
    (t/is (= 1 (count events)))
    (t/is (= {other-id {:id other-id}}
             (:files (ptk/update (first events) state))))))
