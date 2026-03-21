package org.draken.usagi.core.model.parcelable

import android.os.Parcel
import kotlinx.parcelize.Parceler
import org.draken.usagi.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource

class MangaSourceParceler : Parceler<MangaSource> {

	override fun create(parcel: Parcel): MangaSource = MangaSource(parcel.readString())

	override fun MangaSource.write(parcel: Parcel, flags: Int) {
		parcel.writeString(name)
	}
}
