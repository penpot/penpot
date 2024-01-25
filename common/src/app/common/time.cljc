;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.time
  "A new cross-platform date and time API. It should be prefered over
  a platform specific implementation found on `app.util.time`."
  #?(:cljs
     (:require
      ["luxon" :as lxn])
     :clj
     (:import
      java.time.Instant
      java.time.Duration)))

#?(:cljs
   (def DateTime lxn/DateTime))

#?(:cljs
   (def Duration lxn/Duration))

(defn now
  []
  #?(:clj (Instant/now)
     :cljs (.local ^js DateTime)))

(defn instant
  [s]
  #?(:clj  (Instant/ofEpochMilli s)
     :cljs (.fromMillis ^js DateTime s #js {:zone "local" :setZone false})))

#?(:cljs
   (extend-protocol IComparable
     DateTime
     (-compare [it other]
       (if ^boolean (.equals it other)
         0
         (if (< (inst-ms it) (inst-ms other)) -1 1)))

     Duration
     (-compare [it other]
       (if ^boolean (.equals it other)
         0
         (if (< (inst-ms it) (inst-ms other)) -1 1)))))


#?(:cljs
   (extend-type DateTime
     cljs.core/IEquiv
     (-equiv [o other]
       (and (instance? DateTime other)
            (== (.valueOf o) (.valueOf other))))))

#?(:cljs
   (extend-protocol cljs.core/Inst
     DateTime
     (inst-ms* [inst] (.toMillis ^js inst))

     Duration
     (inst-ms* [inst] (.toMillis ^js inst)))

   :clj
   (extend-protocol clojure.core/Inst
     Duration
     (inst-ms* [v] (.toMillis ^Duration v))

     Instant
     (inst-ms* [v] (.toEpochMilli ^Instant v))))
