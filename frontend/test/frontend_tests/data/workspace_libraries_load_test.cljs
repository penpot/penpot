;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-libraries-load-test
  "Tests for a library of the open file that the backend can not load."
  (:require
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defn- http-error
  [code]
  (ex-info "http error" {:type :server-error :code code}))

(t/deftest ^:async a-library-that-can-not-load-stays-connected-and-empty
  (let [file-id    (uuid/next)
        library-id (uuid/next)
        library    {:id library-id :name "Broken" :is-indirect false}
        handler    (dwl/on-library-load-error file-id library)
        events     (atom [])]
    (await (async/observe (handler (http-error :file-not-loadable))
                          :on-next #(swap! events conj %)))
    (let [state (reduce (fn [state event] (ptk/update event state))
                        {:files {}}
                        @events)
          lib   (get-in state [:files library-id])]
      (t/is (= [::dwl/libraries-fetched] (mapv ptk/type @events)))
      (t/is (= "Broken" (:name lib)))
      (t/is (= file-id (:library-of lib)))
      (t/is (empty? (get-in lib [:data :components])))
      (t/is (empty? (get-in lib [:data :colors])))
      (t/is (empty? (get-in lib [:data :typographies]))))))

(t/deftest ^:async other-library-errors-go-on
  (let [library {:id (uuid/next) :name "Lib"}
        handler (dwl/on-library-load-error (uuid/next) library)
        failed  (atom [])]
    (doseq [cause [(http-error :unexpected)
                   (ex-info "offline" {:type :network})
                   (js/Error. "boom")]]
      (await (async/observe (handler cause)
                            :on-error #(swap! failed conj %))))
    (t/is (= 3 (count @failed)))))

(t/deftest ^:async linking-keeps-the-name-of-a-broken-library-linked-by-another
  ;; Linking `direct` also loads `indirect`, a library of `direct` that is
  ;; not in the "Add library" list; both fail to load.
  (let [file-id     (uuid/next)
        direct-id   (uuid/next)
        indirect-id (uuid/next)
        store       (ptk/store {:state {:current-file-id file-id
                                        :files {file-id {:id file-id
                                                         :data (ctf/make-file-data file-id)}}
                                        :shared-files {direct-id {:id direct-id :name "Direct"}}}
                                :on-error #(t/is false (str %))})
        libraries   [{:id direct-id :name "Direct" :is-indirect false}
                     {:id indirect-id :name "Indirect" :is-indirect true}]
        lib-name    #(get-in @store [:files % :name])]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [cmd _]
                             (case cmd
                               :link-file-to-library (rx/of [indirect-id])
                               :get-file (rx/throw (http-error :file-not-loadable))
                               :get-file-libraries (rx/of libraries)
                               :get-file-object-thumbnails (rx/of {})
                               (rx/of nil))))}
       (ptk/emit! store (dwl/link-file-to-library file-id direct-id))
       (await (async/wait-for #(and (lib-name direct-id) (lib-name indirect-id))
                              "both libraries are stored"))
       (t/is (= "Direct" (lib-name direct-id)))
       (t/is (= "Indirect" (lib-name indirect-id)))
       (t/is (empty? (get-in @store [:files indirect-id :data :components])))))))
