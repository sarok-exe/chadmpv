chadmpv = {}
function chadmpv.show_text(text)
    mp.set_property("user-data/chadmpv/show_text", text)
end
function chadmpv.hide_ui()
    mp.set_property("user-data/chadmpv/toggle_ui", "hide")
end
function chadmpv.show_ui()
    mp.set_property("user-data/chadmpv/toggle_ui", "show")
end
function chadmpv.toggle_ui()
    mp.set_property("user-data/chadmpv/toggle_ui", "toggle")
end
function chadmpv.show_subtitle_settings()
   mp.set_property("user-data/chadmpv/show_panel", "subtitle_settings")
end
function chadmpv.show_subtitle_delay()
    mp.set_property("user-data/chadmpv/show_panel", "subtitle_delay")
end
function chadmpv.show_audio_delay()
    mp.set_property("user-data/chadmpv/show_panel", "audio_delay")
end
function chadmpv.show_video_filters()
    mp.set_property("user-data/chadmpv/show_panel", "video_filters")
end
function chadmpv.set_button_title(text)
   mp.set_property("user-data/chadmpv/set_button_title", text)
end
function chadmpv.reset_button_title(text)
    mp.set_property("user-data/chadmpv/reset_button_title", "unused")
end
function chadmpv.show_button()
    mp.set_property("user-data/chadmpv/toggle_button", "show")
end
function chadmpv.hide_button()
    mp.set_property("user-data/chadmpv/toggle_button", "hide")
end
function chadmpv.toggle_button()
    mp.set_property("user-data/chadmpv/toggle_button", "toggle")
end
function chadmpv.seek_by(value)
    mp.set_property("user-data/chadmpv/seek_by", value)
end
function chadmpv.seek_to(value)
    mp.set_property("user-data/chadmpv/seek_to", value)
end
function chadmpv.seek_by_with_text(value, text)
    mp.set_property("user-data/chadmpv/seek_by_with_text", value .. "|" .. text)
end
function chadmpv.seek_to_with_text(value, text)
    mp.set_property("user-data/chadmpv/seek_to_with_text", value .. "|" .. text)
end
function chadmpv.show_software_keyboard()
    mp.set_property("user-data/chadmpv/software_keyboard", "show")
end
function chadmpv.hide_software_keyboard()
    mp.set_property("user-data/chadmpv/software_keyboard", "hide")
end
function chadmpv.toggle_software_keyboard()
    mp.set_property("user-data/chadmpv/software_keyboard", "toggle")
end
return chadmpv
