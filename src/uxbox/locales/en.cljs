;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.locales.en)

(defonce +locales+
  {"ds.projects" "PROJECTS"
   "ds.num-projects" ["No projects"
                      "%s project"
                      "%s projects"]
   "ds.project-ordering" "Sort by"
   "ds.project-ordering.by-name" "name"
   "ds.project-ordering.by-last-update" "last update"
   "ds.project-ordering.by-creation-date" "creation date"
   "ds.project-search.placeholder" "Search..."

   "ds.elements" "ELEMENTS"
   "ds.icons" "ICONS"
   "ds.images" "IMAGES"
   "ds.colors" "COLORS"
   "ds.library-title" "Library: "
   "ds.standard-title" "STANDARD"
   "ds.your-libraries-title" "YOUR LIBRARIES"
   "ds.num-elements" ["%s element"
                      "%s elements"]

   "ds.recent-colors" "Recent colors"
   "ds.element-options" "Element options"
   "ds.draw-tools" "Draw tools"
   "ds.sitemap" "Sitemap"
   "ds.document-history" "Document history"

   "ds.help.rect" "Box (Ctrl + B)"
   "ds.help.circle" "Circle (Ctrl + E)"
   "ds.help.line" "Line (Ctrl + L)"

   "profile.password-saved" "Password saved successfully!"

   "history.alert-message" "You are seeng version %s"

   "errors.api.form.old-password-not-match" "Incorrect old password"

   "errors.form.required" "This field is mandatory"
   "errors.form.string" "Should be string"
   "errors.form.number" "Invalid number"
   "errors.form.integer" "Invalid integer"
   "errors.form.bool" "Should be bool"
   "errors.form.min-len" "Should be great than %s"
   "errors.form.max-len" "Should be less than %s"
   "errors.form.color" "Should be a valid color string"
   "errors.form.password-not-match" "Password does not match"

   "errors.generic" "Something work has happened."
   "errors.auth.unauthorized" "Username or passwords seems to be wrong."
   "errors.profile.update-password" "Error updating password, probably your old password is wrong."
   })
