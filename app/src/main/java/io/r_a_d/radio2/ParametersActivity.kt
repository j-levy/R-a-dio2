package io.r_a_d.radio2

import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import io.r_a_d.radio2.databinding.ActivityParametersBinding
import io.r_a_d.radio2.preferences.*


class ParametersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParametersBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParametersBinding.inflate(this.layoutInflater)
        // UI Launch
        setTheme(R.style.AppTheme_Parameters)
        setContentView(R.layout.activity_parameters)

        // my_child_toolbar is defined in the layout file
        setSupportActionBar(binding.parametersToolbar)

        // Get a support ActionBar corresponding to this toolbar and enable the Up button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        val extra: String? = if (savedInstanceState == null) {
            intent.extras?.getString("action")
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                savedInstanceState.getSerializable("action", String::class.java)
            else
                savedInstanceState.getSerializable("action") as String
        }

        val fragmentToLoad = when(extra) {
            ActionOpenParam.ALARM.name -> AlarmFragment()
            ActionOpenParam.SLEEP.name -> SleepFragment()
            ActionOpenParam.CUSTOMIZE.name -> CustomizeFragment()
            ActionOpenParam.STREAMER_NOTIFICATION_SERVICE.name -> StreamerNotifServiceFragment()
            else -> MainPreferenceFragment()
        }


        supportFragmentManager
            .beginTransaction()
            .replace(R.id.parameters_host_container, fragmentToLoad)
            .commit()
    }

    // Make the Up button function as back instead of always bringing us to the main activity
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}