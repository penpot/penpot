;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.layers
  "Events related with layers transformations"
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; -- Opacity ----------------------------------------------------------

;; The opacity of an element can be changed by typing numbers on the keyboard:
;; 1 --> 0.1
;; 2 --> 0.2
;; 3 --> 0.3
;; 4 --> 0.4
;; ...
;; 9 --> 0.9
;; 0 --> 1
;; 00 --> 0%
;; The user can also type a more exact number:
;; 45 --> 45%
;; 05 --> 5%

(defn calculate-opacity [numbers]
  (let [total (->> numbers
                   (str/join "")
                   (d/parse-integer))]
    (if (= numbers [0])
      1
      (/ total (mth/pow 10 (count numbers))))))

(defn set-opacity
  [opacity]
  (ptk/reify ::set-opacity
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects    (dsh/lookup-page-objects state)
            selected   (dsh/lookup-selected state {:omit-blocked? true})
            shapes     (map #(get objects %) selected)
            shapes-ids (->> shapes
                            (map :id))]
        (rx/of (dwsh/update-shapes shapes-ids #(assoc % :opacity opacity)))))))

(defn pressed-opacity
  [opacity]
  (let [same-event (js/Symbol "same-event")]
    (ptk/reify ::pressed-opacity
      IDeref
      (-deref [_] opacity)

      ptk/UpdateEvent
      (update [_ state]
        (if (nil? (:press-opacity-id state)) ;; avoiding duplicated events
          (assoc state :press-opacity-id same-event)
          state))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (not= same-event (:press-opacity-id state))
          (rx/empty)
          (let [opacity-events (->> stream ;; Stop buffering after time without opacities
                                    (rx/filter (ptk/type? ::pressed-opacity))
                                    (rx/buffer-time 600)
                                    (rx/take 1)
                                    (rx/map #(set-opacity (calculate-opacity (map deref %)))))]
            (rx/concat
             (rx/of (set-opacity (calculate-opacity [opacity]))) ;; First opacity is always fired
             (rx/merge
              opacity-events
              (rx/of (pressed-opacity opacity)))
             (rx/of (fn [state]
                      (dissoc state :press-opacity-id))))))))))
