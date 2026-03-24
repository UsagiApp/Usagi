package org.koitharu.kotatsu.parsers.model

public interface MangaSource {

	public val name: String

	public val title: String
		get() = name
	
	public val locale: String
		get() = ""
		
	public val contentType: ContentType
		get() = ContentType.OTHER
		
	public val isBroken: Boolean
		get() = false
}
