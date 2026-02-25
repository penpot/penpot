(ns app.main.data.nitrate
  (:require
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn show-nitrate-popup
  [popup-type]
  (ptk/reify ::show-nitrate-popup
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! ::get-nitrate-connectivity {})
           (rx/map (fn [connectivity]
                     (modal/show popup-type (or connectivity {}))))))))

(defn go-to-nitrate-cc
  []
  (st/emit! (dom/open-new-window "/control-center/")))

(defn go-to-nitrate-billing
  []
  (st/emit! (rt/nav-raw :href "/control-center/licenses/billing")))


