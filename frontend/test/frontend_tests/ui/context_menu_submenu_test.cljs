;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.context-menu-submenu-test
  (:require
   [app.main.ui.workspace.context-menu :as ctx-menu]
   [cljs.test :as t :include-macros true]))

;; A 240px wide menu, its submenus 240 x 300, in a 1200 x 800 window.
;; Submenus keep 10px from their menu and 8px from the window edges.

(defn- position
  [& {:keys [menu-left entry-top submenu-height window-width window-height]
      :or {menu-left 100 entry-top 200 submenu-height 300
           window-width 1200 window-height 800}}]
  (ctx-menu/submenu-position
   {:menu    {:left menu-left :right (+ menu-left 240)}
    :entry   {:top entry-top}
    :submenu {:width 240 :height submenu-height}
    :window  {:width window-width :height window-height}}))

(t/deftest submenu-on-the-right-of-its-menu
  (t/is (= {:left 350 :top 200} (position))
        "beside the menu, at the height of its entry"))

(t/deftest submenu-touching-the-right-edge-stays-on-the-right
  ;; 700 + 240 + 10 + 240 = 1190 <= 1200
  (t/is (= {:left 950 :top 200} (position :menu-left 700))))

(t/deftest submenu-on-the-left-when-it-would-overflow-the-window
  (t/is (= {:left 650 :top 200} (position :menu-left 900))
        "beside the left side of the menu")
  (t/is (= {:left 1150 :top 200} (position :menu-left 900 :window-width 1700))
        "back on the right in a wider window"))

(t/deftest submenu-never-past-the-left-edge-of-the-window
  (t/is (= 0 (:left (position :menu-left 100 :window-width 500)))
        "no room on either side"))

(t/deftest submenu-moved-up-to-stay-inside-the-window
  (t/is (= 492 (:top (position :entry-top 700)))
        "8px above the bottom: 800 - 300 - 8")
  (t/is (= 700 (:top (position :entry-top 700 :window-height 1200)))
        "at the entry in a taller window"))

(t/deftest submenu-taller-than-the-window-starts-at-its-top
  (t/is (= 8 (:top (position :entry-top 700 :submenu-height 900)))))
