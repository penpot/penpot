;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.icons
  (:require-macros [uxbox.main.ui.icons :refer [icon-xref]])
  (:require [rumext.alpha :as mf]))

(def action (icon-xref :action))
(def actions (icon-xref :actions))
(def align-bottom (icon-xref :align-bottom))
(def align-middle (icon-xref :align-middle))
(def align-top (icon-xref :align-top))
(def alignment (icon-xref :alignment))
(def arrow (icon-xref :arrow))
(def arrow-down (icon-xref :arrow-down))
(def arrow-end (icon-xref :arrow-end))
(def arrow-slide (icon-xref :arrow-slide))
(def artboard (icon-xref :artboard))
(def at (icon-xref :at))
(def auto-fix (icon-xref :auto-fix))
(def auto-height (icon-xref :auto-height))
(def auto-width (icon-xref :auto-width))
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
(def icon-empty (icon-xref :icon-empty))
(def icon-lock (icon-xref :icon-lock))
(def icon-set (icon-xref :icon-set))
(def image (icon-xref :image))
(def infocard (icon-xref :infocard))
(def interaction (icon-xref :interaction))
(def layers (icon-xref :layers))
(def letter-spacing (icon-xref :letter-spacing))
(def library (icon-xref :library))
(def libraries (icon-xref :libraries))
(def line (icon-xref :line))
(def line-height (icon-xref :line-height))
(def loader (icon-xref :loader))
(def lock (icon-xref :lock))
(def lock-open (icon-xref :lock-open))
(def logo (icon-xref :uxbox-logo))
(def logout (icon-xref :logout))
(def logo-icon (icon-xref :uxbox-logo-icon))
(def lowercase (icon-xref :lowercase))
(def mail (icon-xref :mail))
(def minus (icon-xref :minus))
(def move (icon-xref :move))
(def msg-error (icon-xref :msg-error))
(def msg-success (icon-xref :msg-success))
(def msg-warning (icon-xref :msg-warning))
(def msg-info (icon-xref :msg-info))
(def navigate (icon-xref :navigate))
(def options (icon-xref :options))
(def organize (icon-xref :organize))
(def palette (icon-xref :palette))
(def pencil (icon-xref :pencil))
(def picker (icon-xref :picker))
(def pin (icon-xref :pin))
(def play (icon-xref :play))
(def plus (icon-xref :plus))
(def radius (icon-xref :radius))
(def recent (icon-xref :recent))
(def redo (icon-xref :redo))
(def rotate (icon-xref :rotate))
(def ruler (icon-xref :ruler))
(def ruler-tool (icon-xref :ruler-tool))
(def save (icon-xref :save))
(def search (icon-xref :search))
(def shape-halign-center (icon-xref :shape-halign-center))
(def shape-halign-left (icon-xref :shape-halign-left))
(def shape-halign-right (icon-xref :shape-halign-right))
(def shape-hdistribute (icon-xref :shape-hdistribute))
(def shape-valign-bottom (icon-xref :shape-valign-bottom))
(def shape-valign-center (icon-xref :shape-valign-center))
(def shape-valign-top (icon-xref :shape-valign-top))
(def shape-vdistribute (icon-xref :shape-vdistribute))
(def size-horiz (icon-xref :size-horiz))
(def size-vert (icon-xref :size-vert))
(def strikethrough (icon-xref :strikethrough))
(def stroke (icon-xref :stroke))
(def sublevel (icon-xref :sublevel))
(def text (icon-xref :text))
(def text-align-center (icon-xref :text-align-center))
(def text-align-justify (icon-xref :text-align-justify))
(def text-align-left (icon-xref :text-align-left))
(def text-align-right (icon-xref :text-align-right))
(def titlecase (icon-xref :titlecase))
(def toggle (icon-xref :toggle))
(def trash (icon-xref :trash))
(def tree (icon-xref :tree))
(def underline (icon-xref :underline))
(def undo (icon-xref :undo))
(def undo-history (icon-xref :undo-history))
(def ungroup (icon-xref :ungroup))
(def unlock (icon-xref :unlock))
(def uppercase (icon-xref :uppercase))
(def user (icon-xref :user))
(def tick (icon-xref :tick))

(def loader-pencil
  (mf/html
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
   (for [[key val] (sort-by first (ns-publics 'uxbox.main.ui.icons))]
     (when (not= key 'debug-icons-preview)
       [:div.icon-item {:key key}
        (deref val)
        [:span (pr-str key)]]))])
