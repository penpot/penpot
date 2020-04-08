;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.builtins.icons
  (:require-macros [uxbox.builtins.icons :refer [icon-xref]])
  (:require [rumext.alpha :as mf :refer-macros [html]]))

(def action (icon-xref :action))
(def actions (icon-xref :actions))
(def align-center (icon-xref :align-center))
(def align-justify (icon-xref :align-justify))
(def align-left (icon-xref :align-left))
(def align-right (icon-xref :align-right))
(def alignment (icon-xref :alignment))
(def arrow (icon-xref :arrow))
(def arrow-down (icon-xref :arrow-down))
(def arrow-end (icon-xref :arrow-end))
(def arrow-slide (icon-xref :arrow-slide))
(def artboard (icon-xref :artboard))
(def box (icon-xref :box))
(def chain (icon-xref :chain))
(def chat (icon-xref :chat))
(def circle (icon-xref :circle))
(def close (icon-xref :close))
(def copy (icon-xref :copy))
(def curve (icon-xref :curve))
(def download (icon-xref :download))
(def exit (icon-xref :exit))
(def export (icon-xref :export))
(def eye (icon-xref :eye))
(def eye-closed (icon-xref :eye-closed))
(def file-html (icon-xref :file-html))
(def file-svg (icon-xref :file-svg))
(def fill (icon-xref :fill))
(def folder (icon-xref :folder))
(def folder-zip (icon-xref :folder-zip))
(def full-screen (icon-xref :full-screen))
(def full-screen-off (icon-xref :full-screen-off))
(def grid (icon-xref :grid))
(def grid-snap (icon-xref :grid-snap))
(def icon-set (icon-xref :icon-set))
(def image (icon-xref :image))
(def infocard (icon-xref :infocard))
(def layers (icon-xref :layers))
(def line (icon-xref :line))
(def loader (icon-xref :loader))
(def lock (icon-xref :lock))
(def lock-open (icon-xref :lock-open))
(def logo (icon-xref :uxbox-logo))
(def logo-icon (icon-xref :uxbox-logo-icon))
(def mail (icon-xref :mail))
(def move (icon-xref :move))
(def options (icon-xref :options))
(def organize (icon-xref :organize))
(def palette (icon-xref :palette))
(def pencil (icon-xref :pencil))
(def picker (icon-xref :picker))
(def pin (icon-xref :pin))
(def play (icon-xref :play))
(def radius (icon-xref :radius))
(def redo (icon-xref :redo))
(def rotate (icon-xref :rotate))
(def ruler (icon-xref :ruler))
(def ruler-tool (icon-xref :ruler-tool))
(def save (icon-xref :save))
(def search (icon-xref :search))
(def size-horiz (icon-xref :size-horiz))
(def size-vert (icon-xref :size-vert))
(def stroke (icon-xref :stroke))
(def sublevel (icon-xref :sublevel))
(def text (icon-xref :text))
(def toggle (icon-xref :toggle))
(def trash (icon-xref :trash))
(def tree (icon-xref :tree))
(def undo (icon-xref :undo))
(def undo-history (icon-xref :undo-history))
(def ungroup (icon-xref :ungroup))
(def unlock (icon-xref :unlock))
(def user (icon-xref :user))
(def recent (icon-xref :recent))

(def loader-pencil
  (html
   [:svg
    {:viewBox "0 0 677.34762 182.15429"
     :height "182"
     :width "667"
     :id "loader-pencil"}
    [:g
     [:path
      {:id "body-body"
       :d
       "M128.273 0l-3.9 2.77L0 91.078l128.273 91.076 549.075-.006V.008L128.273 0zm20.852 30l498.223.006V152.15l-498.223.007V30zm-25 9.74v102.678l-49.033-34.813-.578-32.64 49.61-35.225z"}]
     [:path
      {:id "loader-line"
       :d
       "M134.482 157.147v25l518.57.008.002-25-518.572-.008z"}]]]))

(mf/defc debug-icons-preview
  {::mf/wrap-props false}
  [props]
  [:section.debug-icons-preview
   (for [[key val] (ns-publics 'uxbox.builtins.icons)]
     (when (not= key 'debug-icons-preview)
       [:div.icon-item {:key key}
        (deref val)
        [:span (pr-str key)]]))])
