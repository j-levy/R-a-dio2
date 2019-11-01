package io.r_a_d.radio2.playerstore

import androidx.lifecycle.MutableLiveData

class Song(artistTitle: String = "") {
    val title: MutableLiveData<String> = MutableLiveData()
    val artist: MutableLiveData<String> = MutableLiveData()
    val type: MutableLiveData<Int> = MutableLiveData()
    val startTime: MutableLiveData<Long> = MutableLiveData()
    val stopTime: MutableLiveData<Long> = MutableLiveData()

    init {
        setTitleArtist(artistTitle)
        type.value = 0
        startTime.value =  System.currentTimeMillis()
        stopTime.value = System.currentTimeMillis() + 1000
    }

    override fun toString() : String {
        return "${artist.value} - ${title.value} | type=${type.value} | times ${startTime.value} - ${stopTime.value}\n"
    }

    fun setTitleArtist(data: String)
    {
        //val data = "Anzai Yukari, Fujita Akane, Noguchi Yuri, Numakura Manami, Suzaki Aya, Uchida Aya - Spatto! Spy & Spice" // TODO DEBUG with a big title.
        val hyphenPos = data.indexOf(" - ")
        try {
            if (hyphenPos < 0)
                throw ArrayIndexOutOfBoundsException()
            if (artist.value != data.substring(0, hyphenPos))
                artist.value = data.substring(0, hyphenPos)
            if (title.value != data.substring(hyphenPos + 3))
                title.value = data.substring(hyphenPos + 3)
        } catch (e: Exception) {
            if (artist.value != "")
                artist.value = ""
            if (title.value != data)
                title.value = data
        }
    }

    fun copy(song: Song) {
        this.title.value = song.title.value
        this.artist.value = song.artist.value
        this.startTime.value = song.startTime.value
        this.stopTime.value = song.stopTime.value
        this.type.value = song.type.value
    }
}