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
   "ds.num-files" ["No files"
                   "%s file"
                   "%s files"]
   "ds.new-file" "+ New File"
   "ds.project-title" "Your projects"
   "ds.project-new" "+ New project"
   "ds.project-thumbnail.alt" "Project title"

   "ds.ordering" "Sort by"
   "ds.ordering.by-name" "name"
   "ds.ordering.by-last-update" "last update"
   "ds.ordering.by-creation-date" "creation date"
   "ds.search.placeholder" "Search..."
   "ds.uploaded-at" "Uploaded at %s"
   "ds.updated-at" "Updated %s"

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
   "ds.icon-new" "+ New icon"

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
   "ds.default-library-title" "Unnamed Collection (%s)"

   "ds.project.placeholder" "New project name"
   "ds.project.new" "New project"

   "common.accept" "Accept"
   "common.cancel" "Cancel"

   "workspace.sidebar.icons" "Icons"
   "workspace.sidebar.element-options" "Element options"
   "workspace.sidebar.draw-tools" "Draw tools"
   "workspace.sidebar.sitemap" "Sitemap"
   "workspace.sidebar.layers" "Layers"
   "workspace.sidebar.document-history" "Document history"

   "ds.page.placeholder" "Page name"
   "ds.page.new" "New page"
   "ds.page.edit" "Edit page"

   "ds.history.versions" "History"
   "ds.history.pinned" "Pinned"

   "workspace.header.rect" "Box (Ctrl + B)"
   "workspace.header.circle" "Circle (Ctrl + E)"
   "workspace.header.line" "Line (Ctrl + L)"
   "workspace.header.text" "Text"
   "workspace.header.path" "Path"
   "workspace.header.curve" "Curve"
   "workspace.header.ruler" "Ruler"
   "workspace.header.canvas" "Canvas"

   "ds.user.profile" "Profile"
   "ds.user.password" "Password"
   "ds.user.notifications" "Notifications"
   "ds.user.exit" "Exit"

   "workspace.header.sitemap" "Sitemap (Ctrl + Shift + M)"
   "workspace.header.draw-tools" "Draw tools (Ctrl + Shift + S)"
   "workspace.header.color-palette" "Color Palette (---)"
   "workspace.header.icons" "Icons (Ctrl + Shift + I)"
   "workspace.header.layers" "Layers (Ctrl + Shift + L)"
   "workspace.header.element-options" "Element options (Ctrl + Shift + O)"
   "workspace.header.document-history" "History (Ctrl + Shift + H)"
   "workspace.header.undo" "Undo (Ctrl + Z)"
   "workspace.header.redo" "Redo (Ctrl + Shift + Z)"
   "workspace.header.download" "Download (Ctrl + E)"
   "workspace.header.image" "Image (Ctrl + I)"
   "workspace.header.rules" "Rules"
   "workspace.header.grid" "Grid (Ctrl + G)"
   "workspace.header.grid-snap" "Snap to grid"
   "workspace.header.align" "Align (Ctrl + A)"
   "workspace.header.view-mode" "View mode (Ctrl + P)"

   "workspace.options.radius" "Radius"
   "workspace.options.size" "Size"
   "workspace.options.width" "Width"
   "workspace.options.height" "Height"
   "workspace.options.stroke.style" "Style"
   "workspace.options.stroke.none" "None"
   "workspace.options.stroke.solid" "Solid"
   "workspace.options.stroke.dotted" "Dotted"
   "workspace.options.stroke.dashed" "Dashed"
   "workspace.options.stroke.mixed" "Mixed"
   "workspace.options.position" "Position"
   "workspace.options.rotation" "Rotation"
   "workspace.options.opacity" "Opacity"
   "workspace.options.color" "Color"
   "workspace.options.background-color" "Background color"
   "workspace.options.font-family" "Font Family"
   "workspace.options.font-weight" "Font Size & Weight"
   "workspace.options.font-size" "Font Size"
   "workspace.options.line-height-letter-spacing" "Line height and Letter spacing"
   "workspace.options.line-height" "Line Height"
   "workspace.options.letter-spacing" "Letter Spacing"
   "workspace.options.text-align" "Text Alignment"
   "workspace.options.name" "Name"
   "workspace.options.go" "Go go go!"
   "workspace.options.measures" "Size, position & rotation"
   "workspace.options.font-options" "Fonts & Font Size"
   "workspace.options.rotation-radius" "Rotation & Radius"
   "workspace.options.stroke" "Stroke"
   "workspace.options.grid-options" "Grid settings"

   "element.fill" "Fill"
   "element.text" "Text"
   "element.interactions" "Interactions"

   "image.new" "New image"
   "image.select" "Select from library"
   "image.upload" "Upload file"
   "image.import-library" "Import image from library"

   "auth.email-or-username" "Email or Username"
   "auth.password" "Password"
   "auth.signin" "Sign in"
   "auth.forgot-password" "Forgot your password?"
   "auth.no-account" "Don't have an account?"
   "auth.message.recovery-token-sent" "Password recovery link sent to your inbox."
   "auth.message.password-recovered" "Password successfully recovered."

   "register.fullname.placeholder" "Full Name"
   "register.username.placeholder" "Username"
   "register.email.placeholder" "Email"
   "register.password.placeholder" "Password"
   "register.get-started" "Get started"
   "register.already-have-account" "Already have an account?"

   "recovery-request.username-or-email.placeholder" "username or email address"
   "recovery-request.recover-password" "Recover password"
   "recovery-request.go-back" "Go back!"

   "recover.password.placeholder" "Password"
   "recover.recover-password" "Recover password"
   "recover.go-back" "Go back!"

   "settings.profile" "PROFILE"
   "settings.password" "PASSWORD"
   "settings.notifications" "NOTIFICATIONS"
   "settings.exit" "EXIT"

   "settings.profile.profile-saved" "Profile saved successfully!"
   "settings.profile.section-basic-data" "Name, username and email"
   "settings.profile.section-i18n-data" "Default language"
   "settings.profile.your-name" "Your name"
   "settings.profile.your-username" "Your username"
   "settings.profile.your-email" "Your email"

   "settings.choose-color-theme" "Choose a theme"
   "settings.profile.light-theme" "Light theme"
   "settings.profile.dark-theme" "Dark theme"
   "settings.profile.high-contrast-theme" "High-contrast theme"
   "settings.profile.your-avatar" "Your avatar"

   "settings.password.password-saved" "Password saved successfully!"
   "settings.password.wrong-old-password" "Wrong old password"
   "settings.password.change-password" "Change password"
   "settings.password.old-password" "Old password"
   "settings.password.new-password" "New password"
   "settings.password.confirm-password" "Confirm password"

   "settings.notifications.notifications-saved" "Notifications preferences saved successfully!"
   "settings.notifications.prototype-notifications" "Prototype notifications"
   "settings.notifications.description" "Get a roll up of prototype changes in your inbox."
   "settings.notifications.none" "None"
   "settings.notifications.every-hour" "Every hour"
   "settings.notifications.every-day" "Every day"

   "settings.update-settings" "Update settings"

   "history.alert-message" "You are seeing version %s"

   "errors.api.form.unexpected-error" "An unexpected error occurred."
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
