;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.copy-button-test
  "The shared inspect copy button reports success only once the
  clipboard write resolves (#12648). On insecure origins the write
  rejects, and the button used to show the copied tick anyway."
  (:require
   [app.main.store :as st]
   [app.main.ui.components.copy-button :as copy-button]
   [app.util.i18n :refer [tr]]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(defonce ^:private original-clipboard
  (unchecked-get js/navigator "clipboard"))

(defn- restore-clipboard
  []
  (unchecked-set js/navigator "clipboard" original-clipboard))

(t/use-fixtures :each {:after restore-clipboard})

(defn- get-notification
  [event]
  (:notification (ptk/update event {})))

(t/deftest ^:async unavailable-clipboard-shows-error-instead-of-success
  (let [emitted (atom [])
        copied  (atom 0)]
    (unchecked-set js/navigator "clipboard" nil)
    (await (mock/with-mocks*
             {st/emit! (mock/stub (fn [& events] (swap! emitted into events) nil))}
             (await (copy-button/copy-text "width: 100px;" #(swap! copied inc)))))
    (t/is (= 0 @copied)
          "the copied tick must not show when nothing reached the clipboard")
    (t/is (= 1 (count @emitted)))
    (t/is (= :app.main.data.notifications/show (ptk/type (first @emitted))))
    (let [notification (get-notification (first @emitted))]
      (t/is (= :error (:level notification)))
      (t/is (= (tr "errors.clipboard-api-unavailable") (:content notification))))))

(t/deftest ^:async rejected-write-shows-generic-error
  (let [emitted (atom [])
        copied  (atom 0)]
    (unchecked-set js/navigator "clipboard"
                   #js {:writeText (fn [_]
                                     (js/Promise.reject (js/Error. "Document is not focused.")))})
    (await (mock/with-mocks*
             {st/emit! (mock/stub (fn [& events] (swap! emitted into events) nil))}
             (await (copy-button/copy-text "width: 100px;" #(swap! copied inc)))))
    (t/is (= 0 @copied))
    (t/is (= 1 (count @emitted)))
    (let [notification (get-notification (first @emitted))]
      (t/is (= :error (:level notification)))
      (t/is (= (tr "errors.clipboard-copy-failed") (:content notification))))))

(t/deftest ^:async successful-write-shows-success-without-error
  (let [emitted (atom [])
        copied  (atom 0)
        written (atom nil)]
    (unchecked-set js/navigator "clipboard"
                   #js {:writeText (fn [text]
                                     (reset! written text)
                                     (js/Promise.resolve))})
    (await (mock/with-mocks*
             {st/emit! (mock/stub (fn [& events] (swap! emitted into events) nil))}
             (await (copy-button/copy-text "width: 100px;" #(swap! copied inc)))))
    (t/is (= "width: 100px;" @written))
    (t/is (= 1 @copied))
    (t/is (empty? @emitted))))
