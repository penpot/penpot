;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.local-storage-test
  (:require
   [app.plugins.local-storage :as storage]
   [app.plugins.register :as r]
   [cljs.test :as t :include-macros true]))

(t/deftest remove-item-removes-the-prefixed-key
  (let [data      (atom {})
        fake      #js {}
        plugin-id "plugin-a"]
    (set! (.-getItem fake) (fn [key] (get @data key)))
    (set! (.-setItem fake) (fn [key value] (swap! data assoc key value)))
    (set! (.-removeItem fake) (fn [key] (swap! data dissoc key)))
    (set! (.-keys fake) (fn [] (to-array (keys @data))))
    (with-redefs [r/check-permission (constantly true)
                  storage/local-storage fake]
      (let [proxy (storage/local-storage-proxy plugin-id)]
        (.setItem proxy "key" "value")
        (t/is (= "value" (.getItem proxy "key")))
        (.removeItem proxy "key")
        (t/is (nil? (.getItem proxy "key")))
        (t/is (empty? @data))))))
