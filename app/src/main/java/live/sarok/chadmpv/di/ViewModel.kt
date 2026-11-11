package live.sarok.chadmpv.di

import live.sarok.chadmpv.ui.custombuttons.CustomButtonsScreenViewModel
import live.sarok.chadmpv.ui.library.VideoLibraryViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val ViewModelModule = module {
  viewModelOf(::CustomButtonsScreenViewModel)
  viewModelOf(::VideoLibraryViewModel)
}
