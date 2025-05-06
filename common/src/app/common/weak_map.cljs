;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.weak-map
  "A value based weak-map implementation (CLJS/JS)")

(deftype ValueWeakMap [^js/Map data ^js/FinalizationRegistry registry]
  Object
  (clear [_]
    (.clear data))
  (delete [_ key]
    (.delete data key))
  (get [_ key]
    (if-let [ref (.get data key)]
      (.deref ^WeakRef ref)
      nil))
  (set [_ key val]
    (.set data key (js/WeakRef. val))
    (.register registry val key)
    nil))

(defn create
  []
  (let [data (js/Map.)
        registry (js/FinalizationRegistry. #(.delete data %))]
    (ValueWeakMap. data registry)))
