;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.locales.en)

(defonce locales
  {"ds.projects" "PROJECTS"
   "ds.num-projects" ["No projects"
                      "%s project"
                      "%s projects"]
   "ds.project-title" "Your projects"
   "ds.project-new" "+ New project"

   "ds.ordering" "Sort by"
   "ds.ordering.by-name" "name"
   "ds.ordering.by-last-update" "last update"
   "ds.ordering.by-creation-date" "creation date"
   "ds.search.placeholder" "Search..."
   "ds.uploaded-at" "Uploaded at %s"

   "ds.confirm-title" "Are you sure?"
   "ds.confirm-ok" "Ok"
   "ds.confirm-cancel" "Cancel"

   "ds.multiselect-bar.copy" "Copy"
   "ds.multiselect-bar.copy-to-library" "Copy to library"
   "ds.multiselect-bar.move" "Move"
   "ds.multiselect-bar.move-to-library" "Move to library"
   "ds.multiselect-bar.rename" "Rename"
   "ds.multiselect-bar.delete" "Delete"

   "ds.elements" "ELEMENTS"
   "ds.num-elements" ["%s element"
                      "%s elements"]

   "ds.icons" "ICONS"
   "ds.num-icons" ["No icons"
                   "%s icon"
                   "%s icons"]
   "ds.your-icons-title" "YOUR ICONS"
   "ds.store-icons-title" "ICONS STORE"
   "ds.icons-collection.new" "+ New collection"
   "ds.icon.new" "+ New icon"

   "ds.images" "IMAGES"
   "ds.num-images" ["No images"
                    "%s image"
                    "%s images"]
   "ds.your-images-title" "YOUR IMAGES"
   "ds.store-images-title" "IMAGES STORE"
   "ds.images-collection.new" "+ New library"
   "ds.image-new" "+ New image"

   "ds.colors" "COLORS"
   "ds.num-colors" ["No colors"
                    "%s color"
                    "%s colors"]
   "ds.your-colors-title" "YOUR COLORS"
   "ds.store-colors-title" "COLORS STORE"
   "ds.colors-collection.new" "+ New library"
   "ds.color-new" "+ New color"
   "ds.color-lightbox.title" "New color"
   "ds.color-lightbox.add" "+ Add color"

   "ds.library-title" "Library: "
   "ds.standard-title" "STANDARD"
   "ds.your-libraries-title" "YOUR LIBRARIES"

   "ds.recent-colors" "Recent colors"
   "ds.element-options" "Element options"
   "ds.draw-tools" "Draw tools"
   "ds.sitemap" "Sitemap"
   "ds.document-history" "Document history"

   "ds.help.rect" "Box (Ctrl + B)"
   "ds.help.circle" "Circle (Ctrl + E)"
   "ds.help.line" "Line (Ctrl + L)"
   "ds.help.text" "Text"
   "ds.help.path" "Path"
   "ds.help.curve" "Curve"
   "ds.help.ruler" "Ruler"

   "ds.user.profile" "Profile"
   "ds.user.password" "Password"
   "ds.user.notifications" "Notifications"
   "ds.user.exit" "Exit"
   
   "auth.message.recovery-token-sent" "Password recovery link sent to your inbox."
   "auth.message.password-recovered" "Password successfully recovered."

   "settings.profile" "PROFILE"
   "settings.password" "PASSWORD"
   "settings.notifications" "NOTIFICATIONS"
   "settings.profile-saved" "Profile saved successfully!"
   "settings.password-saved" "Password saved successfully!"
   "settings.wrong-old-password" "Wrong old password"
   "settings.change-password" "Change password"
   "settings.old-password" "Old password"
   "settings.new-password" "New password"
   "settings.confirm-password" "Confirm password"
   "settings.update-settings" "Update settings"

   "history.alert-message" "You are seeing version %s"
   "errors.api.form.old-password-not-match" "Incorrect old password"
   "errors.api.form.registration-disabled" "The registration is currently disabled."
   "errors.api.form.email-already-exists" "The email is already in use by another user."
   "errors.api.form.username-already-exists" "The username is already in use by another user."
   "errors.api.form.user-not-exists" "Username or email does not matches any existing user."
   "errors.form.required" "This field is mandatory"
   "errors.form.string" "Should be string"
   "errors.form.number" "Invalid number"
   "errors.form.integer" "Invalid integer"
   "errors.form.bool" "Should be boolean"
   "errors.form.min-len" "Should be greater than %s"
   "errors.form.max-len" "Should be lesser than %s"
   "errors.form.color" "Should be a valid color string"
   "errors.form.password-not-match" "Password does not match"
   "errors.auth.unauthorized" "Username or password seems to be wrong."
   "errors.auth.invalid-recovery-token" "The recovery token is invalid."
   "errors.profile.update-password" "Error updating password, probably your old password is wrong."

   "errors.network" "Unable to connect to backend server."
   "errors.generic" "Something wrong has happened."
   "errors.conflict" "Conflict on saving data, please refresh and try again."

   })
