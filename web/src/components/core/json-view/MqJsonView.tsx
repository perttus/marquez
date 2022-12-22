// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import ReactJson from 'react-json-view'
import createStyles from '@material-ui/core/styles/createStyles'
import withStyles, { WithStyles } from '@material-ui/core/styles/withStyles'
import { Theme } from '@material-ui/core'
import { MqInputBase } from '../input-base/MqInputBase'

const styles = (theme: Theme) =>
  createStyles({
    input: {
      marginBottom: `${theme.spacing(2)}px`,
    }
  })

interface OwnProps {
  data: object
  placeholder?: string
}

interface StateProps {
  search: string
}

type JsonViewProps = WithStyles<typeof styles> & OwnProps

const InputSearchJsonView = withStyles((theme: Theme) =>
  createStyles({
    input: {
      padding: `${theme.spacing(1)}px ${theme.spacing(2)}px`,
    }
  })
)(MqInputBase)

class MqJsonView extends React.Component<JsonViewProps, StateProps> {
  constructor(props: JsonViewProps) {
    super(props)
    this.state = {
      search: '',
    }
  }

  onSearch = (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    this.setState({ search: event.target.value })
  }

  render() {
    const { data, placeholder = '', classes } = this.props
    const { search } = this.state

    return (
      <>
        <InputSearchJsonView
          className={classes.input}
          onChange={event => this.onSearch(event)}
          value={search}
          autoComplete={'off'}
          id={'json-view'}
          placeholder={placeholder}
        />
        <ReactJson
          src={data}
          theme={"rjv_white"}
          collapsed={2}
          displayDataTypes={false}
          enableClipboard={false}
          highlightSearch={search}
        />
      </>
    )
  }
}

export default withStyles(styles)(MqJsonView)